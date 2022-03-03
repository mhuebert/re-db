(ns re-db.history
  (:require [re-db.core :as db]
            [re-db.util :refer [swap->]]))

(defn last-tx [history] (-> history :log first :tx))
(defn current-tx [history] (:tx history))
(defn current? [history] (= (last-tx history) (current-tx history)))
(def traveling? (complement current?))

(defn from-conn [conn & {:keys [length] :or {length ##Inf}}]
  (let [!history (atom {:tx 0 :log (list {:tx 0}) :conn conn})]
    (db/listen! conn ::history (fn [_ {:as tx-report :keys [db-after datoms to-tx]}]
                                 (prn :logging-tx-report :to to-tx datoms)
                                 (if to-tx
                                   (prn :only-moving-tx)
                                   #_#_if (= (:tx db-after) (:tx @!history))
                                   (let [next-tx (inc (:tx @!history))
                                         tx-report (-> tx-report
                                                       (dissoc :db-before :db-after)
                                                       (assoc :tx next-tx))]
                                     (swap-> !history
                                             (assoc :tx next-tx)
                                             (update :log (comp #(take length %) conj) tx-report))))))
    !history))

(defn travel-txs [history travel-to]
  {:pre [(int? travel-to) (not (neg? travel-to))]}
  (let [[start end] (sort [travel-to (current-tx history)])
        datoms (into []
                     (comp (drop-while #(<= (:tx %) start))
                           (take (- end start))
                           (map :datoms))
                     (reverse (:log history)))]
    (assert (<= travel-to (last-tx history)) (str "Cannot move past the end of history. to: " travel-to ", last: " (last-tx history)))
    (case (compare travel-to (current-tx history))
      1 ;; forward
      [[:db/datoms (apply concat datoms)]]
      -1 ;; backward
      [[:db/datoms-reverse (apply concat datoms)]]
      0 ;; ;; no-op
      nil)))

(defn db-as-of
  "Returns db with value at `tx`"
  ([history tx] (db-as-of history @(:conn history) tx))
  ([history db tx]
   (assert (some (comp #(= tx %) :tx) (:log history)) (str "tx " tx " not found in history."))
   (:db-after (db/transaction db (travel-txs history tx) {}))))

(defn as-of
  "Returns conn with db-value at `tx`"
  [!history tx]
  (atom (db-as-of @!history tx)))