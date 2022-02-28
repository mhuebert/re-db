(ns re-db.travel-test
  (:require [clojure.test :refer [is are deftest testing]]
            [re-db.core :as db]
            [re-db.history :as history]
            [re-db.schema :as schema]
            [re-db.test-helpers :refer [throws]]))

(deftest time-travel
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