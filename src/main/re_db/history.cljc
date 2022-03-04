(ns re-db.history
  (:require [re-db.core :as db]
            [re-db.util :refer [swap-> take-until]]))

(defn last-tx [history] (-> history :log first :tx))
(defn current-tx [history] (:tx @(:conn history)))
(defn current? [history] (= (last-tx history) (current-tx history)))
(def traveling? (complement current?))

(defn from-conn [conn & {:keys [length
                                log-snapshots?]
                         :or {length ##Inf
                              log-snapshots? true}}]
  (let [!history (atom {:log (list {:tx 0 :datoms [] :db-after @conn})
                        :conn conn})]
    (db/listen! conn ::history (fn [_ tx-report]
                                 (let [tx-report (-> tx-report
                                                     (dissoc :db-before)
                                                     (cond-> (not log-snapshots?)
                                                             (dissoc :db-after)))]
                                   (swap! !history update :log (comp #(take length %) conj) tx-report))))
    !history))

(defn travel-txs [history db destination-tx]
  {:pre [(int? destination-tx) (not (neg? destination-tx))]}

  ;; ensure history contains current & destination tx's
  (let [history-txs (into #{} (map :tx) (:log history))]
    (assert (history-txs destination-tx) (str "History does not contain destination tx " destination-tx))
    (assert (history-txs (:tx db)) (str "History does not contain db tx " (:tx db))))

  (let [[start end] (sort [destination-tx (:tx db)])
        datoms (into [] (comp
                         (drop-while #(not= end (:tx %)))
                         (take-while #(not= start (:tx %)))
                         (mapcat :datoms))
                     (:log history))]
    (case (compare destination-tx (current-tx history))
      1 ;; forward
      [[:db/datoms datoms]]
      -1 ;; backward
      [[:db/datoms-reverse datoms]]
      0 ;; ;; no-op
      nil)))

(defn db-as-of
  "Returns db with value at `tx`"
  ([history tx] (db-as-of history @(:conn history) tx))
  ([history db tx]
   (assert (some #(= tx (:tx %)) (:log history))
           (str "tx " tx " not found in history."))
   (->> (:log history)
        (some #(when (= tx (:tx %))
                 (or (:db-after %)
                     (:db-after (db/transaction db (travel-txs history db tx) {}))))))))

(defn as-of
  "Returns conn with db-value at `tx`"
  [!history tx]
  (atom (db-as-of @!history tx)))