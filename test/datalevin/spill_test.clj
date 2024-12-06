(ns datalevin.spill-test
  (:require
   [datalevin.spill :as sp]
   [datalevin.util :as u]
   [taoensso.nippy :as nippy]
   [clojure.test :refer [deftest testing is]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.clojure-test :as test]
   [clojure.test.check.properties :as prop])
  (:import
   [datalevin.spill SpillableVector SpillableMap SpillableSet]))

;; (if (u/graal?)
;;   (require 'datalevin.binding.graal)
;;   (require 'datalevin.binding.java))

(deftest vec-before-spill-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 0)

    (is (vector? vs))
    (is (nil? (seq vs)))
    (is (= "[]" (.toString vs)))
    (is (= [] vs))
    (is (nil? (get vs 0)))
    (is (nil? (peek vs)))
    (is (nil? (first vs)))
    (is (nil? (second vs)))
    (is (nil? (last vs)))
    (is (= 0 (.length vs)))
    (is (= 0 (count vs)))
    (is (not (contains? vs 0)))
    (is (thrown? Exception (nth vs 0)))
    (is (= vs []))
    (is (= vs '()))
    (is (not= vs {}))
    (is (not= vs 1))
    (is (not= vs [1]))
    (is (= [] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [] (subvec vs 0)))
    (is (= [] (into [] vs)))
    (is (thrown? Exception (pop vs)))

    (assoc vs 0 0)
    (is (= 0 (vs 0)))
    (is (= [0] vs))
    (is (= vs [0]))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [] (pop vs)))

    (conj vs 0)
    (conj vs 1)
    (conj vs 2)
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))

    (is (= [0 1 5 6 7] (into vs (map inc) [4 5 6])))))

(deftest vec-spill=in-middle-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 0)

    (conj vs 0)
    (is (= [0] vs))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [0] vs))
    (is (= [] (pop vs)))

    (conj vs 0)
    (conj vs 1)

    (vreset! sp/memory-pressure 99)

    (is (= [0 1] vs))

    (conj vs 2)
    (is (= 1 (vs 1)))
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))

    (is (= [0 1 5 6 7] (into vs (map inc) [4 5 6])))))

(deftest vec-spill-at-start-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 99)

    (conj vs 0)
    (is (= 1 (.disk-count vs)))
    (is (= [0] vs))
    (is (= 0 (vs 0)))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [0] vs))
    (is (= [] (pop vs)))

    (conj vs 0)
    (conj vs 1)
    (conj vs 2)
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))
    (is (= (.length vs)
           (+ ^long (sp/memory-count vs) ^long (sp/disk-count vs))))

    (is (= [0 1 5 6 7] (into vs (map inc) [4 5 6])))

    (vreset! sp/memory-pressure 0)))

(deftest map-before-spill-test
  (let [^SpillableMap m (sp/new-spillable-map)]
    (vreset! sp/memory-pressure 0)

    (is (map? m))
    (is (zero? (.size m)))
    (is (empty? m))
    (is (nil? (seq m)))
    (is (= "{}" (.toString m)))
    (is (= {} m))
    (is (= m {}))
    (is (not= {:a 1} m ))
    (is (nil? (get m 0)))
    (is (= 0 (count m)))
    (is (not (contains? m 0)))
    (is (thrown? Exception (nth m 0)))
    (is (not= m []))
    (is (not= m 1))
    (is (= {} (into {} m)))

    (assoc m 0 0)

    (is (not (empty? m)))
    (is (= {0 0} m))
    (is (= m {0 0}))
    (is (= 0 (get m 0)))
    (is (= 0 (m 0)))
    (is (= 1 (count m)))
    (is (contains? m 0))
    (is (not= m {0 :end}))
    (is (= {0 0 1 1} (into {1 1} m)))

    (.put m 1 1)

    (is (= {1 1 0 0} m))
    (is (= m {1 1 0 0}))
    (is (= 1 (get m 1)))
    (is (= 1 (m 1)))
    (is (= 2 (count m)))
    (is (contains? m 1))
    (is (not= m {1 :end}))
    (is (= {0 0 1 1} (into {1 1} m)))

    (.remove m 1)

    (is (not (contains? m 1)))
    (is (= {0 0} m))

    (is (= {0 0 2 2 3 3}
           (into m (map (fn [[k v]] [(u/long-inc k) (u/long-inc v)]))
                 {1 1 2 2})))
    ))

(deftest map-in-middle-spill-test
  (let [^SpillableMap m (sp/new-spillable-map)]
    (vreset! sp/memory-pressure 0)

    (assoc m 0 0)

    (vreset! sp/memory-pressure 99)

    (assoc m 1 1)
    (is (= #{0 1} (.keySet m) ))
    (is (= {1 1 0 0} m))
    (is (= m {1 1 0 0}))
    (is (= 1 (get m 1)))
    (is (= 1 (m 1)))
    (is (= 2 (count m)))
    (is (contains? m 1))
    (is (not= m {1 :end}))
    (is (= {0 0 1 1} (into {1 1} m)))

    (assoc m 2 2)
    (is (= 3 (count m)))
    (is (contains? m 2))

    (dissoc m 1)
    (is (= 2 (count m)))
    (is (not (contains? m 1)))

    (is (= {0 0 2 2 3 3}
           (into m (map (fn [[k v]] [(u/long-inc k) (u/long-inc v)]))
                 {1 1 2 2})))))

(deftest map-after-spill-test
  (let [^SpillableMap m (sp/new-spillable-map)]
    (vreset! sp/memory-pressure 99)

    (is (map? m))
    (is (zero? (.size m)))
    (is (nil? (seq m)))
    (is (= "{}" (.toString m)))
    (is (= {} m ))
    (is (not= {:a 1} m ))
    (is (nil? (get m 0)))
    (is (= 0 (count m)))
    (is (not (contains? m 0)))
    (is (thrown? Exception (nth m 0)))
    (is (not= m []))
    (is (not= m 1))
    (is (= {} (into {} m)))

    (.put m 0 0)

    (is (= {0 0} m))
    (is (= 0 (get m 0)))
    (is (= 0 (m 0)))
    (is (= 1 (count m)))
    (is (contains? m 0))
    (is (not= m {0 :end}))
    (is (= {0 0 1 1} (into {1 1} m)))

    (.put m 1 1)

    (is (= {1 1 0 0} m))
    (is (= m {1 1 0 0}))
    (is (= 1 (get m 1)))
    (is (= 1 (m 1)))
    (is (= 2 (count m)))
    (is (contains? m 1))
    (is (not= m {1 :end}))
    (is (= {0 0 1 1} (into {1 1} m)))

    (.remove m 0)
    (is (= 1 (count m)))
    (is (not (contains? m 0)))

    (is (= {1 1 2 2 3 3}
           (into m (map (fn [[k v]] [(u/long-inc k) (u/long-inc v)]))
                 {1 1 2 2})))
    (vreset! sp/memory-pressure 0)))

(test/defspec spillable-vector-nippy-test
  100
  (prop/for-all
    [k (gen/vector gen/any-equatable)]
    (let [vs (sp/new-spillable-vector k)]
      (vreset! sp/memory-pressure 0)
      (= vs (nippy/fast-thaw (nippy/fast-freeze vs)))
      (vreset! sp/memory-pressure 99)
      (= vs (nippy/fast-thaw (nippy/fast-freeze vs))))))

(deftest test-false
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (conj vs false)
    (vreset! sp/memory-pressure 0)
    (is (= false (vs 0)))
    (= vs (nippy/fast-thaw (nippy/fast-freeze vs))))
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 99)
    (conj vs false)
    (is (= false (vs 0)))
    (= vs (nippy/fast-thaw (nippy/fast-freeze vs)))))

(deftest set-in-middle-spill-test
  (let [^SpillableSet s (sp/new-spillable-set)]
    (vreset! sp/memory-pressure 0)

    (is (set? s))
    (is (empty? s))
    (conj s 0 )
    (is (= #{0} s))
    (is (= s #{0}))

    (vreset! sp/memory-pressure 99)

    (conj s 1)
    (is (set? s))
    (is (= #{1 0} s))
    (is (= s #{1 0}))
    (is (= 1 (get s 1)))
    (is (= 1 (s 1)))
    (is (= 2 (count s)))
    (is (contains? s 1))
    (is (not= s #{1 :end}))
    (is (= #{0 1} (into #{1} s)))

    (conj s 2)
    (is (= 3 (count s)))
    (is (contains? s 2))

    (disj s 1)
    (is (= 2 (count s)))
    (is (not (contains? s 1)))

    (is (= #{0 2 3} (into s (map u/long-inc) #{1 2})))
    (is (= #{0 2 3} (into #{} s)))))
