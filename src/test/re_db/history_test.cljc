(ns re-db.history-test
  (:require [clojure.test :refer [is are deftest testing]]
            [re-db.core :as db]
            [re-db.api :as d]
            [re-db.history2 :as history]
            [re-db.schema :as schema]
            [re-db.test-helpers :refer [throws]]
            [reagent.core :as reagent]))

#_(deftest history
  (let [conn (doto (db/create-conn {:children schema/many})
               (db/transact! [[:db/add 1 :children #{1}]])
               (db/transact! [[:db/add 1 :children #{2 4}]])
               (db/transact! [[:db/retract 1 :children #{1}]]))]
    (doall
     (for [[tx expected] (->> (repeat (seq {0 nil
                                            1 #{1}
                                            2 #{1 2 4}
                                            3 #{2 4}}))
                              (take 5)
                              (apply concat)
                              (shuffle))]
       (do (db/travel! conn tx)
           (is (= expected
                  (:children (db/get-entity @conn 1)))))))
    (throws (db/travel! conn 4))))

(deftest history2
  (d/with-conn {:children schema/many}
    (let [!history (history/from-conn (d/conn))
          _ (do
              (d/transact! [[:db/add 1 :children #{1}]])
              (d/transact! [[:db/add 1 :children #{2 4}]])
              (d/transact! [[:db/retract 1 :children #{1}]]))]
      (prn :transacted (map (juxt :tx (comp #(get % 1) :eav :db-after)) (:log @!history)))
      (doall
       (for [[tx expected] (->> (repeat (seq {0 nil
                                              1 #{1}
                                              2 #{1 2 4}
                                              3 #{2 4}}))
                                (take 5)
                                (apply concat)
                                (shuffle))]
         (d/with-conn (history/as-of !history tx)
           (prn :as-of tx (d/get 1))
           (is (= expected
                  (d/get 1 :children))
               (str "value for tx " tx)))))
      #_(throws (history/travel! !conn 4)))))