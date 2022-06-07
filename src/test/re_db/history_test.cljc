(ns re-db.history-test
  (:require [clojure.test :refer [is are deftest testing]]
            [re-db.api :as d]
            [re-db.history :as history]
            [re-db.schema :as schema]))

(deftest history-snapshots
  "History based on resetting to snapshots"
  (d/with-conn {:children schema/many}
    (let [!history (history/from-conn (d/conn) :mode :snapshot)
          txs (cons (history/last-tx @!history)
                    (map :tx [(d/transact! [[:db/add 1 :children 1]])
                              (d/transact! [[:db/add 1 :children 2]
                                            [:db/add 1 :children 4]])
                              (d/transact! [[:db/retract 1 :children 1]])]))]
      (doall
       (for [[tx expected] (->> (repeat (seq (zipmap txs [nil #{1} #{1 2 4} #{2 4}])))
                                (take 5)
                                (apply concat)
                                (shuffle))]
         (d/with-conn (history/as-of !history tx)
           (is (= expected
                  (d/get 1 :children))
               (str "value for tx " tx)))))

      ;; removed test - history txs are dependent on global state
      #_(is (thrown? #?(:clj AssertionError :cljs js/Error) (history/as-of !history 4))))))

(deftest history-datoms
  "History based on (re)play of datoms"
  (d/with-conn {:children schema/many}
    (let [!history (history/from-conn (d/conn) :mode :diff)
          txs (cons (history/last-tx @!history)
                    (map :tx [(d/transact! [[:db/add 1 :children 1]])
                              (d/transact! [[:db/add 1 :children 2]
                                            [:db/add 1 :children 4]])
                              (d/transact! [[:db/retract 1 :children 1]])]))]
      (doall
       (for [[tx expected] (->> (repeat (seq (zipmap txs [nil #{1} #{1 2 4} #{2 4}])))
                                (take 5)
                                (apply concat)
                                (shuffle))]
         (d/with-conn (history/as-of !history tx)
           (is (= expected
                  (d/get 1 :children))
               (str "value for tx " tx)))))
      #_(is (thrown? #?(:clj AssertionError :cljs js/Error) (history/as-of !history 4))))))