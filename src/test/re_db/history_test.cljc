(ns re-db.history-test
  (:require [clojure.test :refer [is are deftest testing]]
            [re-db.core :as db]
            [re-db.api :as d]
            [re-db.history :as history]
            [re-db.schema :as schema]
            [re-db.test-helpers :refer [throws]]))

(deftest history
  (d/with-conn {:children schema/many}
    (let [!history (history/from-conn (d/conn))
          _ (do
              (d/transact! [[:db/add 1 :children #{1}]])
              (d/transact! [[:db/add 1 :children #{2 4}]])
              (d/transact! [[:db/retract 1 :children #{1}]]))]
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
      (throws (history/as-of !history 4)))))