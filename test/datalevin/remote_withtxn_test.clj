(ns datalevin.remote-withtxn-test
  (:require
   [datalevin.core :as d]
   [datalevin.test.core :as tdc :refer [server-fixture]]
   [clojure.test :as t :refer [is deftest testing use-fixtures]]))

(use-fixtures :each server-fixture)

(def query '[:find ?c .
             :in $ ?e
             :where [?e :counter ?c]])

(deftest new-value-invisible-outside-test
  (let [dir  "dtlv://datalevin:datalevin@localhost/remote-with-tx"
        conn (d/create-conn dir)]
    (d/with-transaction [cn conn]
      (is (nil? (d/q query @cn 1)))
      (d/transact! cn [{:db/id 1 :counter 1}])
      (is (= 1 (d/q query @cn 1)))
      (is (nil? (d/q query @conn 1))))
    (is (= 1 (d/q query @conn 1)))
    (d/close conn)))

(deftest abort-test
  (let [dir  "dtlv://datalevin:datalevin@localhost/remote-with-tx"
        conn (d/create-conn dir)]
    (d/transact! conn [{:db/id 1 :counter 1}])
    (d/with-transaction [cn conn]
      (d/transact! cn [{:db/id 1 :counter 2}])
      (is (= 2 (d/q query @cn 1)))
      (d/abort-transact cn))
    (is (= 1 (d/q query @conn 1)))
    (d/close conn)))

(deftest same-client-concurrent-test
  (let [dir  "dtlv://datalevin:datalevin@localhost/remote-with-tx"
        conn (d/create-conn dir {:client-opts {:pool-size 1}})]
    (d/transact! conn [{:db/id 1 :counter 1}])
    (let [count-f
          #(d/with-transaction [cn conn]
             (let [^long now (d/q query @cn 1)]
               (d/transact! cn [{:db/id 1 :counter (inc now)}])
               (d/q query @cn 1)))]
      (is (= (set [2 3 4])
             (set (pcalls count-f count-f count-f)))))
    (is (= 4 (d/q query @conn 1)))
    (d/close conn)))

(deftest diff-clients-concurrent-test
  (let [dir  "dtlv://datalevin:datalevin@localhost/remote-with-tx"
        conn (d/create-conn dir)]
    (d/transact! conn [{:db/id 1 :counter 4}])
    (let [count-f
          #(d/with-transaction [cn (d/create-conn
                                     dir {:client-opts {:pool-size 1}})]
             (let [^long now (d/q query @cn 1)]
               (d/transact! cn [{:db/id 1 :counter (inc now)}])
               (d/q query @cn 1)))]
      (is (= (set [5 6 7])
             (set (pcalls count-f count-f count-f)))))
    (is (= 7 (d/q query @conn 1)))
    (d/close conn)))

(deftest with-txn-map-resize-test
  (let [dir    "dtlv://datalevin:datalevin@localhost/remote-with-tx"
        conn   (d/create-conn dir nil {:kv-opts {:mapsize 1}})
        query1 '[:find ?d .
                 :in $ ?e
                 :where [?e :content ?d]]
        query2 '[:find ?d .
                 :in $ ?e
                 :where [?e :description ?d]]
        prior  "prior data"
        big    "bigger than 1MB"]

    (d/with-transaction [cn conn]
      (d/transact! cn [{:content prior}])
      (is (= prior (d/q query1 @cn 1)))
      (d/transact! cn [{:description big
                        :numbers     (range 1000000)}])
      (is (= big (d/q query2 @cn 2))))

    (is (= prior (d/q query1 @conn 1)))
    (is (= big (d/q query2 @conn 2)))

    (d/close conn)))