(ns ^:no-doc ^:lean-ns datalevin.db
  (:require
   [clojure.walk]
   [clojure.data]
   [clojure.set]
   [me.tonsky.persistent-sorted-set :as set]
   [me.tonsky.persistent-sorted-set.arrays :as arrays]
   [datalevin.constants :as c :refer [e0 tx0 emax txmax]]
   [datalevin.lru :as lru]
   [datalevin.datom :as d
    :refer [datom datom-added datom?]]
   [datalevin.util :as u
    :refer [case-tree raise defrecord-updatable cond+]]
   [datalevin.storage :as s]
   [datalevin.remote :as r]
   [datalevin.client :as cl]
   [datalevin.inline :refer [update]])
  #?(:cljs
     (:require-macros [datalevin.util
                       :refer [case-tree raise defrecord-updatable cond+]]))
  #?(:clj
     (:import [datalevin.datom Datom]
              [datalevin.storage IStore]
              [datalevin.remote DatalogStore]
              [datalevin.lru LRU]
              [datalevin.bits Retrieved]
              [java.net URI]
              [java.util.concurrent ConcurrentHashMap])
     (:refer-clojure :exclude [update])))

(defonce dbs (atom {}))

;;;;;;;;;; Searching

(defprotocol ISearch
  (-search [data pattern])
  (-count [data pattern])
  (-first [data pattern])
  (-last [data pattern]))

(defprotocol IIndexAccess
  (-populated? [db index components])
  (-datoms [db index components])
  (-range-datoms [db index start-datom end-datom])
  (-first-datom [db index components])
  (-seek-datoms [db index components])
  (-rseek-datoms [db index components])
  (-index-range [db attr start end]))

(defprotocol IDB
  (-schema [db])
  (-rschema [db])
  (-attrs-by [db property]))

;; ----------------------------------------------------------------------------

(declare empty-db resolve-datom validate-attr components->pattern)
#?(:cljs (declare pr-db))

(defrecord TxReport [db-before db-after tx-data tempids tx-meta])

#?(:clj
   (defmethod print-method TxReport [^TxReport rp, ^java.io.Writer w]
     (binding [*out* w]
       (pr {:datoms-transacted (count (:tx-data rp))}))))

(defn db-transient [db]
  (-> db
      (assoc :eavt (set/sorted-set-by d/cmp-datoms-eavt))
      (assoc :avet (set/sorted-set-by d/cmp-datoms-avet))
      (assoc :veat (set/sorted-set-by d/cmp-datoms-veat))
      (update :eavt transient)
      (update :avet transient)
      (update :veat transient)))

(defn db-persistent! [db]
  (-> db
      (update :eavt persistent!)
      (update :avet persistent!)
      (update :veat persistent!)))

(defprotocol Searchable
  (-searchable? [_]))

(extend-type #?(:clj Object :cljs object)
  Searchable
  (-searchable? [_] false))

(extend-type nil
  Searchable
  (-searchable? [_] false))

(defonce ^:private caches (ConcurrentHashMap.))

(defn refresh-cache
  ([store]
   (refresh-cache store (s/last-modified store)))
  ([store target]
   (.put ^ConcurrentHashMap caches store
         (lru/lru c/+cache-limit+ target))))

(defmacro wrap-cache
  [store pattern body]
  `(let [cache# (.get ^ConcurrentHashMap caches ~store)]
     (if-some [cached# (get ^LRU cache# ~pattern nil)]
       cached#
       (let [res# ~body]
         (.put ^ConcurrentHashMap caches ~store (assoc cache# ~pattern res#))
         res#))))

(defn vpred
  [v]
  (cond
    (string? v)  (fn [x] (if (string? x) (.equals ^String v x) false))
    (int? v)     (fn [x] (if (int? x) (= (long v) (long x)) false))
    (keyword? v) (fn [x] (.equals ^Object v x))
    (nil? v)     (fn [x] (nil? x))
    :else        (fn [x] (= v x))))

(defrecord-updatable DB [^IStore store eavt avet veat max-eid max-tx
                         pull-patterns pull-attrs
                         hash]

  clojure.lang.IEditableCollection
  (empty [db]         (with-meta (empty-db (s/dir store) (s/schema store))
                        (meta db)))
  (asTransient [db] (db-transient db))

  clojure.lang.ITransientCollection
  (conj [db key] (throw (ex-info "datalevin.DB/conj! is not supported" {})))
  (persistent [db] (db-persistent! db))

  Searchable
  (-searchable? [_] true)

  IDB
  (-schema [_] (wrap-cache store :schema (s/schema store)))
  (-rschema [_] (wrap-cache store :rschema (s/rschema store)))
  (-attrs-by [db property] ((-rschema db) property))

  ISearch
  (-search
    [db pattern]
    (let [[e a v _] pattern]
      (wrap-cache
        store
        [:search e a v]
        (case-tree
          [e a (some? v)]
          [(s/fetch store (datom e a v)) ; e a v
           (s/slice store :eav (datom e a c/v0) (datom e a c/vmax)) ; e a _
           (s/slice-filter store :eav
                           (fn [^Datom d] ((vpred v) (.-v d)))
                           (datom e nil nil)
                           (datom e nil nil))  ; e _ v
           (s/slice store :eav (datom e nil nil) (datom e nil nil)) ; e _ _
           (s/slice store :ave (datom e0 a v) (datom emax a v)) ; _ a v
           (s/slice store :ave (datom e0 a nil) (datom emax a nil)) ; _ a _
           (s/slice store :vea (datom e0 nil v) (datom emax nil v)) ; _ _ v
           (s/slice store :eav (datom e0 nil nil) (datom emax nil nil))])))) ; _ _ _

  (-first
    [db pattern]
    (let [[e a v _] pattern
          pred      (vpred v)]
      (wrap-cache
        store
        [:first e a v]
        (case-tree
          [e a (some? v)]
          [(first (s/fetch store (datom e a v))) ; e a v
           (s/head store :eav (datom e a c/v0) (datom e a c/vmax)) ; e a _
           (s/head-filter store :eav
                          (fn [^Datom d] ((vpred v) (.-v d)))
                          (datom e nil nil)
                          (datom e nil nil))  ; e _ v
           (s/head store :eav (datom e nil nil) (datom e nil nil)) ; e _ _
           (s/head store :ave (datom e0 a v) (datom emax a v)) ; _ a v
           (s/head store :ave (datom e0 a nil) (datom emax a nil)) ; _ a _
           (s/head store :vea (datom e0 nil v) (datom emax nil v)) ; _ _ v
           (s/head store :eav (datom e0 nil nil) (datom emax nil nil))])))) ; _ _ _

  (-last
    [db pattern]
    (let [[e a v _] pattern]
      (wrap-cache
        store
        [:last e a v]
        (case-tree
          [e a (some? v)]
          [(first (s/fetch store (datom e a v))) ; e a v
           (s/tail store :eav  (datom e a c/vmax) (datom e a c/v0)) ; e a _
           (s/tail-filter store :eav
                          (fn [^Datom d] ((vpred v) (.-v d)))
                          (datom e nil nil)
                          (datom e nil nil))  ; e _ v
           (s/tail store :eav (datom e nil nil) (datom e nil nil)) ; e _ _
           (s/tail store :ave (datom emax a v) (datom e0 a v)) ; _ a v
           (s/tail store :ave (datom emax a nil) (datom e0 a nil)) ; _ a _
           (s/tail store :vea (datom emax nil v) (datom e0 nil v)) ; _ _ v
           (s/tail store :eav (datom emax nil nil) (datom e0 nil nil))]))))

  (-count
    [db pattern]
    (let [[e a v _] pattern]
      (wrap-cache
        store
        [:count e a v]
        (case-tree
          [e a (some? v)]
          [(s/size store :eav (datom e a v) (datom e a v)) ; e a v
           (s/size store :eav (datom e a c/v0) (datom e a c/vmax)) ; e a _
           (s/size-filter store :eav
                          (fn [^Datom d] ((vpred v) (.-v d)))
                          (datom e nil nil)
                          (datom e nil nil))  ; e _ v
           (s/size store :eav (datom e nil nil) (datom e nil nil)) ; e _ _
           (s/size store :ave (datom e0 a v) (datom emax a v)) ; _ a v
           (s/size store :ave (datom e0 a nil) (datom emax a nil)) ; _ a _
           (s/size store :vea (datom e0 nil v) (datom emax nil v)) ; _ _ v
           (s/datom-count store :eav)])))) ; _ _ _

  IIndexAccess
  (-populated?
    [db index cs]
    (wrap-cache
      store
      [:populated? index cs]
      (s/populated? store index (components->pattern db index cs e0 tx0)
                    (components->pattern db index cs emax txmax))))

  (-datoms
    [db index cs]
    (wrap-cache
      store
      [:datoms index cs]
      (s/slice store index (components->pattern db index cs e0 tx0)
               (components->pattern db index cs emax txmax))))

  (-range-datoms
    [db index start-datom end-datom]
    (wrap-cache
      store
      [:range-datoms index start-datom end-datom]
      (s/slice store index start-datom end-datom)))

  (-first-datom
    [db index cs]
    (wrap-cache
      store
      [:first-datom index cs]
      (s/head store index (components->pattern db index cs e0 tx0)
              (components->pattern db index cs emax txmax))))

  (-seek-datoms
    [db index cs]
    (wrap-cache
      store
      [:seek index cs]
      (s/slice store index (components->pattern db index cs e0 tx0)
               (datom emax nil nil txmax))))

  (-rseek-datoms
    [db index cs]
    (wrap-cache
      store
      [:rseek index cs]
      (s/rslice store index (components->pattern db index cs emax txmax)
                (datom e0 nil nil tx0))))

  (-index-range
    [db attr start end]
    (wrap-cache
      store
      [attr start end]
      (do (validate-attr attr (list '-index-range 'db attr start end))
          (s/slice store :avet (resolve-datom db nil attr start nil e0 tx0)
                   (resolve-datom db nil attr end nil emax txmax)))))

  clojure.data/EqualityPartition
  (equality-partition [x] :datalevin/db))

(defmethod print-method DB [^DB db, ^java.io.Writer w]
  (binding [*out* w]
    (let [store (:dtore db)]
      (pr {:db-name       (s/db-name store)
           :last-modified (s/last-modified store)
           :datom-count   (count :eavt)
           :max-eid       (:max-eid db)
           :max-tx        (:max-tx db)}))))

(defn db?
  "Check if x is an instance of DB, also refresh its cache if it's stale.
  Often used in the :pre condition of a DB access function"
  [x]
  (when (-searchable? x)
    (let [store  (.-store ^DB x)
          target (s/last-modified store)
          cache  ^LRU (.get ^ConcurrentHashMap caches store)]
      (when (< ^long (.-target cache) ^long target)
        (refresh-cache store target)))
    true))

;; ----------------------------------------------------------------------------

(defn- validate-schema-key [a k v expected]
  (when-not (or (nil? v)
                (contains? expected v))
    (throw (ex-info (str "Bad attribute specification for "
                         (pr-str {a {k v}}) ", expected one of " expected)
                    {:error     :schema/validation
                     :attribute a
                     :key       k
                     :value     v}))))

(defn- validate-schema [schema]
  (doseq [[a kv] schema]
    (let [comp? (:db/isComponent kv false)]
      (validate-schema-key a :db/isComponent (:db/isComponent kv) #{true false})
      (when (and comp? (not= (:db/valueType kv) :db.type/ref))
        (raise "Bad attribute specification for " a
               ": {:db/isComponent true} should also have {:db/valueType :db.type/ref}"
               {:error     :schema/validation
                :attribute a
                :key       :db/isComponent})))
    (validate-schema-key a :db/unique (:db/unique kv)
                         #{:db.unique/value :db.unique/identity})
    (validate-schema-key a :db/valueType (:db/valueType kv)
                         c/datalog-value-types)
    (validate-schema-key a :db/cardinality (:db/cardinality kv)
                         #{:db.cardinality/one :db.cardinality/many})
    (validate-schema-key a :db/fulltext (:db/fulltext kv)
                         #{true false})

    ;; tuple should have tupleAttrs
    (when (and (= :db.type/tuple (:db/valueType kv))
               (not (contains? kv :db/tupleAttrs)))
      (raise "Bad attribute specification for " a ": {:db/valueType :db.type/tuple} should also have :db/tupleAttrs"
             {:error     :schema/validation
              :attribute a
              :key       :db/valueType}))

    ;; :db/tupleAttrs is a non-empty sequential coll
    (when (contains? kv :db/tupleAttrs)
      (let [ex-data {:error     :schema/validation
                     :attribute a
                     :key       :db/tupleAttrs}]
        (when (= :db.cardinality/many (:db/cardinality kv))
          (raise a " has :db/tupleAttrs, must be :db.cardinality/one" ex-data))

        (let [attrs (:db/tupleAttrs kv)]
          (when-not (sequential? attrs)
            (raise a " :db/tupleAttrs must be a sequential collection, got: " attrs ex-data))

          (when (empty? attrs)
            (raise a " :db/tupleAttrs can’t be empty" ex-data))

          (doseq [attr attrs
                  :let [ex-data (assoc ex-data :value attr)]]
            (when (contains? (get schema attr) :db/tupleAttrs)
              (raise a " :db/tupleAttrs can’t depend on another tuple attribute: " attr ex-data))

            (when (= :db.cardinality/many (:db/cardinality (get schema attr)))
              (raise a " :db/tupleAttrs can’t depend on :db.cardinality/many attribute: " attr ex-data))))))
    ))

(defn- open-store
  [dir schema {:keys [db-name] :as opts}]
  (if (r/dtlv-uri? dir)
    (let [uri     (URI. dir)
          db-name (cl/parse-db uri)
          store   (r/open dir schema opts)]
      (swap! dbs assoc db-name store)
      store)
    (let [store (s/open dir schema opts)]
      (when db-name (swap! dbs assoc db-name store))
      store)))

(defn new-db
  [^IStore store]
  (refresh-cache store)
  (map->DB
    {:store         store
     :eavt          (set/sorted-set-by d/cmp-datoms-eavt)
     :avet          (set/sorted-set-by d/cmp-datoms-avet)
     :veat          (set/sorted-set-by d/cmp-datoms-veat)
     :max-eid       (s/init-max-eid store)
     :max-tx        (s/max-tx store)
     :pull-patterns (lru/cache 100 :constant)
     :pull-attrs    (lru/cache 100 :constant)}))

(defn ^DB empty-db
  ([] (empty-db nil nil))
  ([dir] (empty-db dir nil))
  ([dir schema] (empty-db dir schema nil))
  ([dir schema opts]
   {:pre [(or (nil? schema) (map? schema))]}
   (validate-schema schema)
   (new-db (open-store dir schema opts))))

(defn ^DB init-db
  ([datoms] (init-db datoms nil nil nil))
  ([datoms dir] (init-db datoms dir nil nil))
  ([datoms dir schema] (init-db datoms dir schema nil))
  ([datoms dir schema opts]
   {:pre [(or (nil? schema) (map? schema))]}
   ;; commented out below in our fork because for import, we rely on datoms being a lazy list and this realizes the whole list
   #_(when-some [not-datom (first (drop-while datom? datoms))]
       (raise "init-db expects list of Datoms, got " (type not-datom)
              {:error :init-db}))
   (validate-schema schema)
   (let [store (open-store dir schema opts)]
     (s/load-datoms store datoms)
     (new-db store))))

(defn close-db [^DB db]
  (let [store ^IStore (.-store db)]
    (.remove ^ConcurrentHashMap caches store)
    (s/close store)
    nil))

(defn db-from-reader [{:keys [schema datoms]}]
  (init-db (map (fn [[e a v tx]] (datom e a v tx)) datoms) schema))

;; ----------------------------------------------------------------------------

(declare entid-strict entid-some ref?)

(defn- resolve-datom [db e a v t default-e default-tx]
  (when a (validate-attr a (list 'resolve-datom 'db e a v t)))
  (datom
    (or (entid-some db e) default-e)  ;; e
    a                                 ;; a
    (if (and (some? v) (ref? db a))   ;; v
      (entid-strict db v)
      v)
    (or (entid-some db t) default-tx))) ;; t

(defn- components->pattern [db index [c0 c1 c2 c3] default-e default-tx]
  (case index
    :eavt (resolve-datom db c0 c1 c2 c3 default-e default-tx)
    :eav  (resolve-datom db c0 c1 c2 c3 default-e default-tx)
    :avet (resolve-datom db c2 c0 c1 c3 default-e default-tx)
    :ave  (resolve-datom db c2 c0 c1 c3 default-e default-tx)
    :veat (resolve-datom db c2 c1 c0 c3 default-e default-tx)
    :vea  (resolve-datom db c2 c1 c0 c3 default-e default-tx)))

;; ----------------------------------------------------------------------------

(defn #?@(:clj  [^Boolean is-attr?]
          :cljs [^boolean is-attr?]) [db attr property]
  (contains? (-attrs-by db property) attr))

(defn #?@(:clj  [^Boolean multival?]
          :cljs [^boolean multival?]) [db attr]
  (is-attr? db attr :db.cardinality/many))

(defn #?@(:clj  [^Boolean ref?]
          :cljs [^boolean ref?]) [db attr]
  (is-attr? db attr :db.type/ref))

(defn #?@(:clj  [^Boolean component?]
          :cljs [^boolean component?]) [db attr]
  (is-attr? db attr :db/isComponent))

(defn #?@(:clj  [^Boolean tuple?]
          :cljs [^boolean tuple?]) [db attr]
  (is-attr? db attr :db.type/tuple))

(defn #?@(:clj  [^Boolean tuple-source?]
          :cljs [^boolean tuple-source?]) [db attr]
  (is-attr? db attr :db/attrTuples))

(defn entid [db eid]
  (cond
    (and (integer? eid) (not (neg? (long eid))))
    (if (<= ^long eid ^long emax)
      eid
      (raise "Highest supported entity id is " emax
             ", got " eid {:error :entity-id :value eid}))

    (sequential? eid)
    (let [[attr value] eid]
      (cond
        (not= (count eid) 2)
        (raise "Lookup ref should contain 2 elements: " eid
               {:error :lookup-ref/syntax, :entity-id eid})
        (not (is-attr? db attr :db/unique))
        (raise "Lookup ref attribute should be marked as :db/unique: " eid
               {:error :lookup-ref/unique, :entity-id eid})
        (nil? value)
        nil
        :else
        (or (-> (set/slice (:avet db)
                           (datom e0 attr value tx0)
                           (datom emax attr value txmax))
                first :e)
            (:e (-first-datom db :avet eid)))))

    #?@(:cljs [(array? eid) (recur db (array-seq eid))])

    (keyword? eid)
    (or (-> (set/slice (:avet db)
                       (datom e0 :db/ident eid tx0)
                       (datom emax :db/ident eid txmax))
            first :e)
        (:e (-first-datom db :avet [:db/ident eid])))

    :else
    (raise "Expected number or lookup ref for entity id, got " eid
           {:error :entity-id/syntax, :entity-id eid})))

(defn entid-strict [db eid]
  (or (entid db eid)
      (raise "Nothing found for entity id " eid
             {:error     :entity-id/missing
              :entity-id eid})))

(defn entid-some [db eid]
  (when eid
    (entid-strict db eid)))

;;;;;;;;;; Transacting

(defn validate-datom [db ^Datom datom]
  (when (and (is-attr? db (.-a datom) :db/unique) (datom-added datom))
    (when-some [found (let [a (.-a datom)
                            v (.-v datom)]
                        (or
                          (not-empty (set/slice (:avet db)
                                                (d/datom e0 a v tx0)
                                                (d/datom emax a v txmax)))
                          (-populated? db :avet [a v])))]
      (raise "Cannot add " datom " because of unique constraint: " found
             {:error     :transact/unique
              :attribute (.-a datom)
              :datom     datom})))
  db)

(defn- validate-attr [attr at]
  (when-not (or (keyword? attr) (string? attr))
    (raise "Bad entity attribute " attr " at " at ", expected keyword or string"
           {:error :transact/syntax, :attribute attr, :context at})))

(defn- validate-val [v at]
  (when (nil? v)
    (raise "Cannot store nil as a value at " at
           {:error :transact/syntax, :value v, :context at})))

(defn- current-tx
  #?(:clj {:inline (fn [report] `(-> ~report :db-before :max-tx long inc))})
  ^long [report]
  (-> report :db-before :max-tx long inc))

(defn- next-eid
  #?(:clj {:inline (fn [db] `(inc (long (:max-eid ~db))))})
  ^long [db]
  (inc (long (:max-eid db))))

#?(:clj
   (defn- ^Boolean tx-id?
     [e]
     (or (identical? :db/current-tx e)
         (.equals ":db/current-tx" e) ;; for datascript.js interop
         (.equals "datomic.tx" e)
         (.equals "datascript.tx" e)))

   :cljs
   (defn- ^boolean tx-id?
     [e]
     (or (= e :db/current-tx)
         (= e ":db/current-tx") ;; for datascript.js interop
         (= e "datomic.tx")
         (= e "datascript.tx"))))

(defn- #?@(:clj  [^Boolean tempid?]
           :cljs [^boolean tempid?])
  [x]
  (or (and (number? x) (neg? ^long x)) (string? x)))

(defn- new-eid? [db ^long eid]
  (> eid ^long (:max-eid db)))

(defn- advance-max-eid [db eid]
  (cond-> db
    (new-eid? db eid)
    (assoc :max-eid eid)))

(defn- allocate-eid
  ([_ report eid]
   (update report :db-after advance-max-eid eid))
  ([tx-time report e eid]
   (let [db   (:db-after report)
         new? (new-eid? db eid)]
     (cond-> report
       (tx-id? e)
       (update :tempids assoc e eid)

       (tempid? e)
       (update :tempids assoc e eid)

       (and (not (tempid? e)) new?)
       (update :tempids assoc eid eid)

       (and (:auto-entity-time? (s/opts (.-store ^DB db))) new?)
       (update :tx-data conj (d/datom eid :db/created-at tx-time))

       true
       (update :db-after advance-max-eid eid)))))

;; In context of `with-datom` we can use faster comparators which
;; do not check for nil (~10-15% performance gain in `transact`)

(defn- with-datom [db ^Datom datom]
  (let [ref? (ref? db (.-a datom))]
    (if (datom-added datom)
      (do
        (validate-datom db datom)
        (cond-> db
          true (update :eavt set/conj datom d/cmp-datoms-eavt-quick)
          true (update :avet set/conj datom d/cmp-datoms-avet-quick)
          ref? (update :veat set/conj datom d/cmp-datoms-veat-quick)
          true (advance-max-eid (.-e datom))))
      (if-some [_ (first
                    (set/slice
                      (:eavt db)
                      (d/datom (.-e datom) (.-a datom) (.-v datom) tx0)
                      (d/datom (.-e datom) (.-a datom) (.-v datom) txmax)))]
        (cond-> db
          true (update :eavt set/disj datom d/cmp-datoms-eavt-quick)
          true (update :avet set/disj datom d/cmp-datoms-avet-quick)
          ref? (update :veat set/conj datom d/cmp-datoms-veat-quick))
        db))))

(defn- queue-tuple [queue tuple idx db e a v]
  (let [tuple-value  (or (get queue tuple)
                         (:v (first (set/slice (:eavt db)
                                               (d/datom e tuple nil tx0)
                                               (d/datom e tuple nil txmax))))
                         (:v (-first-datom db :eavt [e tuple]))
                         (vec (repeat (-> db (-schema) (get tuple) :db/tupleAttrs count) nil)))
        tuple-value' (assoc tuple-value idx v)]
    (assoc queue tuple tuple-value')))

(defn- queue-tuples [queue tuples db e a v]
  (reduce-kv
    (fn [queue tuple idx]
      (queue-tuple queue tuple idx db e a v))
    queue
    tuples))

(defn- transact-report [report datom]
  (let [db      (:db-after report)
        a       (:a datom)
        report' (-> report
                    (assoc :db-after (with-datom db datom))
                    (update :tx-data conj datom))]
    (if (tuple-source? db a)
      (let [e      (:e datom)
            v      (if (datom-added datom) (:v datom) nil)
            queue  (or (-> report' ::queued-tuples (get e)) {})
            tuples (get (-attrs-by db :db/attrTuples) a)
            queue' (queue-tuples queue tuples db e a v)]
        (update report' ::queued-tuples assoc e queue'))
      report')))

(defn #?@(:clj  [^Boolean reverse-ref?]
          :cljs [^boolean reverse-ref?]) [attr]
  (cond
    (keyword? attr)
    (= \_ (nth (name attr) 0))

    (string? attr)
    (boolean (re-matches #"(?:([^/]+)/)?_([^/]+)" attr))

    :else
    (raise "Bad attribute type: " attr ", expected keyword or string"
           {:error :transact/syntax, :attribute attr})))

(defn reverse-ref [attr]
  (cond
    (keyword? attr)
    (if (reverse-ref? attr)
      (keyword (namespace attr) (subs (name attr) 1))
      (keyword (namespace attr) (str "_" (name attr))))

    (string? attr)
    (let [[_ ns name] (re-matches #"(?:([^/]+)/)?([^/]+)" attr)]
      (if (= \_ (nth name 0))
        (if ns (str ns "/" (subs name 1)) (subs name 1))
        (if ns (str ns "/_" name) (str "_" name))))

    :else
    (raise "Bad attribute type: " attr ", expected keyword or string"
           {:error :transact/syntax, :attribute attr})))

(defn- resolve-upserts
  "Returns [entity' upserts]. Upsert attributes that resolve to existing entities
   are removed from entity, rest are kept in entity for insertion. No validation is performed.

   upserts :: {:name  {\"Ivan\"  1}
               :email {\"ivan@\" 2}
               :alias {\"abc\"   3
                       \"def\"   4}}}"
  [db entity]
  (if-some [idents (not-empty (-attrs-by db :db.unique/identity))]
    (let [resolve (fn [a v]
                    (or (:e (first (set/slice (:avet db)
                                              (d/datom e0 a v tx0)
                                              (d/datom emax a v txmax))))
                        (:e (-first-datom db :avet [a v]))))

          split (fn [a vs]
                  (reduce
                    (fn [acc v]
                      (if-some [e (resolve a v)]
                        (update acc 1 assoc v e)
                        (update acc 0 conj v)))
                    [[] {}] vs))]
      (reduce-kv
        (fn [[entity' upserts] a v]
          (validate-attr a entity)
          (validate-val v entity)
          (cond
            (not (contains? idents a))
            [(assoc entity' a v) upserts]

            (and
              (multival? db a)
              (or
                (arrays/array? v)
                (and (coll? v) (not (map? v)))))
            (let [[insert upsert] (split a v)]
              [(cond-> entity'
                 (not (empty? insert)) (assoc a insert))
               (cond-> upserts
                 (not (empty? upsert)) (assoc a upsert))])

            :else
            (if-some [e (resolve a v)]
              [entity' (assoc upserts a {v e})]
              [(assoc entity' a v) upserts])))
        [{} {}]
        entity))
    [entity nil]))

(defn validate-upserts
  "Throws if not all upserts point to the same entity.
   Returns single eid that all upserts point to, or null."
  [entity upserts]
  (let [upsert-ids (reduce-kv
                     (fn [m a v->e]
                       (reduce-kv
                         (fn [m v e]
                           (assoc m e [a v]))
                         m v->e))
                     {} upserts)]
    (if (<= 2 (count upsert-ids))
      (let [[e1 [a1 v1]] (first upsert-ids)
            [e2 [a2 v2]] (second upsert-ids)]
        (raise "Conflicting upserts: " [a1 v1] " resolves to " e1 ", but " [a2 v2] " resolves to " e2
               {:error     :transact/upsert
                :assertion [e1 a1 v1]
                :conflict  [e2 a2 v2]}))
      (let [[upsert-id [a v]] (first upsert-ids)
            eid               (:db/id entity)]
        (when (and
                (some? upsert-id)
                (some? eid)
                (not (tempid? eid))
                (not= upsert-id eid))
          (raise "Conflicting upsert: " [a v] " resolves to " upsert-id ", but entity already has :db/id " eid
                 {:error     :transact/upsert
                  :assertion [upsert-id a v]
                  :conflict  {:db/id eid}}))
        upsert-id))))

;; multivals/reverse can be specified as coll or as a single value, trying to guess
(defn- maybe-wrap-multival [db a vs]
  (cond
    ;; not a multival context
    (not (or (reverse-ref? a)
             (multival? db a)))
    [vs]

    ;; not a collection at all, so definitely a single value
    (not (or (arrays/array? vs)
             (and (coll? vs) (not (map? vs)))))
    [vs]

    ;; probably lookup ref
    (and (= (count vs) 2)
         (is-attr? db (first vs) :db.unique/identity))
    [vs]

    :else vs))


(defn- explode [db entity]
  (let [eid  (:db/id entity)
        ;; sort tuple attrs after non-tuple
        a+vs (apply concat
                    (reduce
                      (fn [acc [a vs]]
                        (update acc (if (tuple? db a) 1 0) conj [a vs]))
                      [[] []] entity))]
    (for [[a vs] a+vs
          :when  (not= a :db/id)
          :let   [_          (validate-attr a {:db/id eid, a vs})
                  reverse?   (reverse-ref? a)
                  straight-a (if reverse? (reverse-ref a) a)
                  _          (when (and reverse? (not (ref? db straight-a)))
                               (raise "Bad attribute " a ": reverse attribute name requires {:db/valueType :db.type/ref} in schema"
                                      {:error :transact/syntax, :attribute a, :context {:db/id eid, a vs}}))]
          v      (maybe-wrap-multival db a vs)]
      (if (and (ref? db straight-a) (map? v)) ;; another entity specified as nested map
        (assoc v (reverse-ref a) eid)
        (if reverse?
          [:db/add v   straight-a eid]
          [:db/add eid straight-a v])))))

(def conjv (fnil conj []))

(defn- transact-add [report [_ e a v tx :as ent]]
  (validate-attr a ent)
  (validate-val  v ent)
  (let [tx               (or tx (current-tx report))
        db               (:db-after report)
        e                (entid-strict db e)
        v                (if (ref? db a) (entid-strict db v) v)
        new-datom        (datom e a v tx)
        multival?        (multival? db a)
        ^Datom old-datom (if multival?
                           (or (first (set/slice (:eavt db)
                                                 (datom e a v tx0)
                                                 (datom e a v txmax)))
                               (-first db [e a v]))
                           (or (first (set/slice (:eavt db)
                                                 (datom e a nil tx0)
                                                 (datom e a nil txmax)))
                               (-first db [e a])))]
    (cond
      (nil? old-datom)
      (transact-report report new-datom)

      (= (.-v old-datom) v)
      ;; Following is a bugfix cherrypicked from datalevin master. The issue was that if we had a transaction with a retraction and then assertion of same datom, the latter assertion was not happening
      ;; 1. Initial issue: https://github.com/juji-io/datalevin/issues/192
      ;;      was fixed in commit: https://github.com/juji-io/datalevin/commit/e34d88b45ded14111e6c8bb213ca65216df2f47e#diff-1ac9ad202f2259dd8a2bb6dde9a5671d39331f6d7f430f923b51164a8d2c2319L859-R865
      ;;      #_(if (is-attr? db a :db/unique)
      ;;          (update report ::tx-redundant conjv new-datom)
      ;;          (transact-report report new-datom))
      ;; 2. However, 1 was incomplete/lead to another issue https://github.com/juji-io/datalevin/issues/207
      ;;      this was then fixed in https://github.com/juji-io/datalevin/commit/6c9aea4e250b4d6db8d83ba1eab0fb4a565c841b
      
      ;; this latest change (which is the state in datalevin master) works because datom equivalence check cehcks only for e a v same. See `datalevin.datom/equiv-datom`
      (if (some #(and (not (datom-added %)) (= % new-datom))
                (:tx-data report))
        ;; special case: retract then transact the same datom
        (transact-report report new-datom)
        (update report ::tx-redundant conjv new-datom))

      :else
      (-> report
          (transact-report (datom e a (.-v old-datom) tx false))
          (transact-report new-datom)))))

(defn- transact-retract-datom [report ^Datom d]
  (let [tx (current-tx report)]
    (transact-report report (datom (.-e d) (.-a d) (.-v d) tx false))))

(defn- retract-components [db datoms]
  (into #{} (comp
              (filter (fn [^Datom d] (component? db (.-a d))))
              (map (fn [^Datom d] [:db.fn/retractEntity (.-v d)]))) datoms))

(defn check-value-tempids [report]
  (if-let [tempids (::value-tempids report)]
    (let [all-tempids (transient tempids)
          reduce-fn   (fn [tempids datom]
                        (if (datom-added datom)
                          (dissoc! tempids (:e datom))
                          tempids))
          unused      (reduce reduce-fn all-tempids (:tx-data report))
          unused      (reduce reduce-fn unused (::tx-redundant report))]
      (if (zero? (count unused))
        (dissoc report ::value-tempids ::tx-redundant)
        (raise "Tempids used only as value in transaction: " (sort (vals (persistent! unused)))
               {:error :transact/syntax, :tempids unused})))
    (dissoc report ::value-tempids ::tx-redundant)))

(declare local-transact-tx-data)

(defn- retry-with-tempid [initial-report report es tempid upserted-eid]
  (if (contains? (:tempids initial-report) tempid)
    (raise "Conflicting upsert: " tempid " resolves"
           " both to " upserted-eid " and " (get-in initial-report [:tempids tempid])
           { :error :transact/upsert })
    ;; try to re-run from the beginning
    ;; but remembering that `tempid` will resolve to `upserted-eid`
    (let [tempids' (-> (:tempids report)
                       (assoc tempid upserted-eid))
          report'  (assoc initial-report :tempids tempids')]
      (local-transact-tx-data report' es))))

(def builtin-fn?
  #{:db.fn/call
    :db.fn/cas
    :db/cas
    :db/add
    :db/retract
    :db.fn/retractAttribute
    :db.fn/retractEntity
    :db/retractEntity})

;; HACK to avoid circular dependency
(def de-entity? (delay (resolve 'datalevin.entity/entity?)))
(def de-entity->txs (delay (resolve 'datalevin.entity/->txs)))

(defn- update-entity-time
  [initial-es tx-time]
  (loop [es     initial-es
         new-es (transient [])]
    (let [[entity & entities] es]
      (cond
        (empty? es)
        (persistent! new-es)

        (nil? entity)
        (recur entities new-es)

        (@de-entity? entity)
        (recur (into entities (reverse (@de-entity->txs entity))) new-es)

        (map? entity)
        (recur entities (conj! new-es (assoc entity :db/updated-at tx-time)))

        (sequential? entity)
        (let [[op e _ _] entity]
          (if (or (= op :db/retractEntity)
                  (= op :db.fn/retractEntity))
            (recur entities (conj! new-es entity))
            (recur entities (-> new-es
                                (conj! entity)
                                (conj! [:db/add e :db/updated-at tx-time])))))

        (datom? entity)
        (let [e (d/datom-e entity)]
          (recur entities (-> new-es
                              (conj! entity)
                              (conj! [:db/add e :db/updated-at tx-time]))))

        :else
        (raise "Bad entity at " entity ", expected map, vector, datom or entity"
               {:error :transact/syntax, :tx-data entity})))))

(defn flush-tuples [report]
  (let [db (:db-after report)]
    (reduce-kv
      (fn [entities eid tuples+values]
        (reduce-kv
          (fn [entities tuple value]
            (let [value   (if (every? nil? value) nil value)
                  current (or (:v (first (set/slice
                                           (:eavt db)
                                           (d/datom eid tuple nil tx0)
                                           (d/datom eid tuple nil txmax))))
                              (:v (-first-datom db :eavt [eid tuple])))]
              (cond
                (= value current) entities
                (nil? value)      (conj entities ^::internal [:db/retract eid tuple current])
                :else             (conj entities ^::internal [:db/add eid tuple value]))))
          entities
          tuples+values))
      []
      (::queued-tuples report))))

(defn- local-transact-tx-data
  ([initial-report initial-es]
   (local-transact-tx-data initial-report initial-es false))
  ([initial-report initial-es simulated?]
   (let [tx-time         (System/currentTimeMillis)
         initial-report' (-> initial-report
                             (update :db-after transient))
         has-tuples?     (not (empty? (-attrs-by (:db-after initial-report) :db.type/tuple)))
         initial-es'     (cond-> initial-es
                           (:auto-entity-time?
                            (s/opts (.-store ^DB (:db-before initial-report))))
                           (update-entity-time tx-time)
                           has-tuples?
                           (interleave (repeat ::flush-tuples)))
         rp
         (loop [report initial-report'
                es     initial-es']
           (cond+
             (empty? es)
             (-> report
                 check-value-tempids
                 (update :tempids assoc :db/current-tx (current-tx report))
                 (update :db-after update :max-tx u/long-inc)
                 (update :db-after persistent!))

             :let [[entity & entities] es]

             (nil? entity)
             (recur report entities)

             (= ::flush-tuples entity)
             (if (contains? report ::queued-tuples)
               (recur
                 (dissoc report ::queued-tuples)
                 (concat (flush-tuples report) entities))
               (recur report entities))

             (@de-entity? entity)
             (recur report
                    (into entities (reverse (@de-entity->txs entity))))


             :let [^DB db      (:db-after report)
                   tempids (:tempids report)]

             (map? entity)
             (let [old-eid (:db/id entity)]
               (cond+
                 ;; :db/current-tx / "datomic.tx" => tx
                 (tx-id? old-eid)
                 (let [id (current-tx report)]
                   (recur (allocate-eid tx-time report old-eid id)
                          (cons (assoc entity :db/id id) entities)))

                 ;; lookup-ref => resolved | error
                 (sequential? old-eid)
                 (let [id (entid-strict db old-eid)]
                   (recur report
                          (cons (assoc entity :db/id id) entities)))

                 ;; upserted => explode | error
                 :let [[entity' upserts] (resolve-upserts db entity)
                       upserted-eid      (validate-upserts entity' upserts)]

                 (some? upserted-eid)
                 (if (and (tempid? old-eid)
                          (contains? tempids old-eid)
                          (not= upserted-eid (get tempids old-eid)))
                   (retry-with-tempid initial-report report initial-es old-eid upserted-eid)
                   (recur (-> (allocate-eid tx-time report old-eid upserted-eid)
                              (update ::tx-redundant conjv
                                      (datom upserted-eid nil nil tx0)))
                          (concat (explode db (assoc entity' :db/id upserted-eid)) entities)))

                 ;; resolved | allocated-tempid | tempid | nil => explode
                 (or (number? old-eid)
                     (nil?    old-eid)
                     (string? old-eid))
                 (let [new-eid    (cond
                                    (nil? old-eid)    (next-eid db)
                                    (tempid? old-eid) (or (get tempids old-eid)
                                                          (next-eid db))
                                    :else             old-eid)
                       new-entity (assoc entity :db/id new-eid)]
                   (recur (allocate-eid tx-time report old-eid new-eid)
                          (concat (explode db new-entity) entities)))

                 ;; trash => error
                 :else
                 (raise "Expected number, string or lookup ref for :db/id, got " old-eid
                        { :error :entity-id/syntax, :entity entity })))

             (sequential? entity)
             (let [[op e a v] entity]
               (cond
                 (= op :db.fn/call)
                 (let [[_ f & args] entity]
                   (recur report (concat (apply f db args) entities)))

                 (and (keyword? op)
                      (not (builtin-fn? op)))
                 (if-some [ident (or (:e
                                      (first
                                        (set/slice
                                          (:avet db)
                                          (d/datom e0 op nil tx0)
                                          (d/datom emax op nil txmax))))
                                     (entid db op))]
                   (let [fun  (or (-> (set/slice
                                        (:eavt db)
                                        (d/datom ident :db/fn nil tx0)
                                        (d/datom ident :db/fn nil txmax))
                                      first :v)
                                  (:v (-first db [ident :db/fn])))
                         args (next entity)]
                     (if (fn? fun)
                       (recur report (concat (apply fun db args) entities))
                       (raise "Entity " op " expected to have :db/fn attribute with fn? value"
                              {:error :transact/syntal, :operation :db.fn/call, :tx-data entity})))
                   (raise "Can’t find entity for transaction fn " op
                          {:error :transact/syntax, :operation :db.fn/call, :tx-data entity}))

                 (and (tempid? e) (not= op :db/add))
                 (raise "Can't use tempid in '" entity "'. Tempids are allowed in :db/add only"
                        { :error :transact/syntax, :op entity })

                 (or (= op :db.fn/cas)
                     (= op :db/cas))
                 (let [[_ e a ov nv] entity
                       e             (entid-strict db e)
                       _             (validate-attr a entity)
                       ov            (if (ref? db a) (entid-strict db ov) ov)
                       nv            (if (ref? db a) (entid-strict db nv) nv)
                       _             (validate-val nv entity)
                       datoms        (clojure.set/union
                                       (set/slice
                                         (:eavt db)
                                         (datom e a nil tx0)
                                         (datom e a nil txmax))
                                       (-search db [e a]))]
                   (if (multival? db a)
                     (if (some (fn [^Datom d] (= (.-v d) ov)) datoms)
                       (recur (transact-add report [:db/add e a nv]) entities)
                       (raise ":db.fn/cas failed on datom [" e " " a " " (map :v datoms) "], expected " ov
                              {:error :transact/cas, :old datoms, :expected ov, :new nv}))
                     (let [v (:v (nth datoms 0))]
                       (if (= v ov)
                         (recur (transact-add report [:db/add e a nv]) entities)
                         (raise ":db.fn/cas failed on datom [" e " " a " " v "], expected " ov
                                {:error :transact/cas, :old (first datoms), :expected ov, :new nv })))))

                 (tx-id? e)
                 (recur (allocate-eid tx-time report e (current-tx report))
                        (cons [op (current-tx report) a v] entities))

                 (and (ref? db a) (tx-id? v))
                 (recur (allocate-eid tx-time report v (current-tx report))
                        (cons [op e a (current-tx report)] entities))

                 (and (ref? db a) (tempid? v))
                 (if-some [resolved (get tempids v)]
                   (recur (update report ::value-tempids assoc resolved v)
                          (cons [op e a resolved] entities))
                   (let [resolved (next-eid db)]
                     (recur (-> (allocate-eid tx-time report v resolved)
                                (update ::value-tempids assoc resolved v))
                            es)))

                 (tempid? e)
                 (let [upserted-eid  (when (is-attr? db a :db.unique/identity)
                                       (or (:e
                                            (first
                                              (set/slice
                                                (:avet db)
                                                (d/datom e0 a v tx0)
                                                (d/datom emax a v txmax))))
                                           (:e (-first-datom db :avet [a v]))))
                       allocated-eid (get tempids e)]
                   (if (and upserted-eid allocated-eid (not= upserted-eid allocated-eid))
                     (retry-with-tempid initial-report report initial-es e upserted-eid)
                     (let [eid (or upserted-eid allocated-eid (next-eid db))]
                       (recur (allocate-eid tx-time report e eid) (cons [op eid a v] entities)))))

                 (and (not (::internal (meta entity)))
                      (tuple? db a))
                 ;; allow transacting in tuples if they fully match already existing values
                 (let [tuple-attrs (get-in (s/schema (.-store db)) [a :db/tupleAttrs])]
                   (if (and
                         (= (count tuple-attrs) (count v))
                         (every? some? v)
                         (every?
                           (fn [[tuple-attr tuple-value]]
                             (let [db-value
                                   (or (:v
                                        (first
                                          (set/slice
                                            (:eavt db)
                                            (d/datom e tuple-attr nil tx0)
                                            (d/datom e tuple-attr nil txmax))))
                                       (:v (-first-datom db :eavt [e tuple-attr])))]
                               (= tuple-value db-value)))
                           (map vector tuple-attrs v)))
                     (recur report entities)
                     (raise "Can’t modify tuple attrs directly: " entity
                            {:error :transact/syntax, :tx-data entity})))

                 (= op :db/add)
                 (recur (transact-add report entity) entities)

                 (and (= op :db/retract) (some? v))
                 (if-some [e (entid db e)]
                   (let [v (if (ref? db a) (entid-strict db v) v)]
                     (validate-attr a entity)
                     (validate-val v entity)
                     (if-some [old-datom (or
                                           (first (set/slice
                                                    (:eavt db)
                                                    (datom e a v tx0)
                                                    (datom e a v txmax)))
                                           (-first db [e a v]))]
                       (recur (transact-retract-datom report old-datom) entities)
                       (recur report entities)))
                   (recur report entities))

                 (or (= op :db.fn/retractAttribute)
                     (= op :db/retract))
                 (if-some [e (entid db e)]
                   (let [_      (validate-attr a entity)
                         datoms (vec
                                  (concat
                                    (set/slice (:eavt db)
                                               (datom e a nil tx0)
                                               (datom e a nil txmax))
                                    (-search db [e a])))]
                     (recur (reduce transact-retract-datom report datoms)
                            (concat (retract-components db datoms) entities)))
                   (recur report entities))

                 (or (= op :db.fn/retractEntity)
                     (= op :db/retractEntity))
                 (if-some [e (entid db e)]
                   (let [e-datoms (vec
                                    (concat
                                      (set/slice (:eavt db)
                                                 (datom e nil nil tx0)
                                                 (datom e nil nil txmax))
                                      (-search db [e])))
                         v-datoms (vec
                                    (concat
                                      (set/slice (:veat db)
                                                 (datom e0 nil e tx0)
                                                 (datom emax nil e txmax))
                                      (-search db [nil nil e])))]
                     (recur (reduce transact-retract-datom report
                                    (concat e-datoms v-datoms))
                            (concat (retract-components db e-datoms) entities)))
                   (recur report entities))

                 :else
                 (raise "Unknown operation at " entity ", expected :db/add, :db/retract, :db.fn/call, :db.fn/retractAttribute, :db.fn/retractEntity or an ident corresponding to an installed transaction function (e.g. {:db/ident <keyword> :db/fn <Ifn>}, usage of :db/ident requires {:db/unique :db.unique/identity} in schema)" {:error :transact/syntax, :operation op, :tx-data entity})))

             (datom? entity)
             (let [[e a v tx added] entity]
               (if added
                 (recur (transact-add report [:db/add e a v tx]) entities)
                 (recur report (cons [:db/retract e a v] entities))))

             :else
             (raise "Bad entity type at " entity ", expected map, vector, or datom"
                    {:error :transact/syntax, :tx-data entity})
             ))
         pstore (.-store ^DB (:db-after rp))]
     (when-not simulated?
       (s/load-datoms pstore (:tx-data rp))
       (refresh-cache pstore))
     rp)))

(defn- remote-tx-result
  [res]
  (if (map? res)
    (let [{:keys [tx-data tempids]} res]
      [tx-data (dissoc tempids :max-eid) (tempids :max-eid)])
    (let [[tx-data tempids] (split-with datom? res)
          max-eid           (-> tempids last second)
          tempids           (into {} (butlast tempids))]
      [tx-data tempids max-eid])))

(defn transact-tx-data
  ([initial-report initial-es simulated?]
   (when-not (or (nil? initial-es)
                 (sequential? initial-es))
     (raise "Bad transaction data " initial-es ", expected sequential collection"
            {:error :transact/syntax, :tx-data initial-es}))
   (let [^DB db (:db-before initial-report)
         store  (.-store db)]
     (if (instance? datalevin.remote.DatalogStore store)
       (try
         (let [res                       (r/tx-data store initial-es simulated?)
               [tx-data tempids max-eid] (remote-tx-result res)]
           (assoc initial-report
                  :db-after (-> (new-db store)
                                (assoc :max-eid max-eid)
                                (#(if simulated?
                                    (update % :max-tx u/long-inc)
                                    %)))
                  :tx-data tx-data
                  :tempids tempids))
         (catch Exception e
           (if (:resized (ex-data e))
             (throw e)
             (local-transact-tx-data initial-report initial-es simulated?))))
       (local-transact-tx-data initial-report initial-es simulated?)))))

(defn tx-data->simulated-report
  [db tx-data]
  {:pre [(db? db)]}
  (when-not (or (nil? tx-data)
                (sequential? tx-data))
    (raise "Bad transaction data " tx-data ", expected sequential collection"
           {:error :transact/syntax, :tx-data tx-data}))
  (let [initial-report (map->TxReport
                         {:db-before db
                          :db-after  db
                          :tx-data   []
                          :tempids   {}
                          :tx-meta   nil})]
    (transact-tx-data initial-report tx-data true)))
