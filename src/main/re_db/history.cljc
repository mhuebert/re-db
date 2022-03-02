(ns re-db.history)

;; TODO
;; - implement history entirely from this namespace
;;   - `listen` to a db and keep a log of transactions (datoms) in a !history
;;   - create a connection from any entry in a !history
;;   - "travel" a connection to a different point in !history (history, conn) => conn (side-effect: travel-to!)
;;     IFF that connection is already pointing at an entry in !history


(defn init-history [db]
  (assoc db :history (list {:tx 0})
            :tx 0))

(defn without-history [db]
  (dissoc db :history :tx))

(defn last-tx [db] (-> (:history db) first :tx))
(defn current-tx [db] (:tx db))
(defn current? [db] (= (last-tx db) (current-tx db)))
(def traveling? (complement current?))

(defn handle-tx-report
  [{:as tx-report :keys [datoms db-before db-after]} {:as options :keys [travel-to]}]
  (cond (empty? datoms) tx-report
        travel-to (update tx-report :db-after assoc
                          ;; update "location" tx + copy history from db-after
                          :tx travel-to
                          :history (:history db-before))
        :else (do
                (assert (= (last-tx db-before) (current-tx db-before))
                        (str "Can only append to history from the edge of time. last-tx: " (last-tx db-before) ", current-tx: " (current-tx db-before)))
                (let [history-length (get-in tx-report [:db-after :schema :db/keep-history] ##Inf)
                      old-tx (last-tx db-before)
                      new-tx (inc old-tx)
                      db-after (-> db-after
                                   (assoc :history (->> (:history db-before)
                                                        (cons (assoc options :tx new-tx :datoms datoms))
                                                        (take history-length)))
                                   (assoc :tx new-tx))]
                  (assoc tx-report :db-after db-after)))))

(defn commit? [db options]
  (or (current? db)
      (:travel-to options)))

(defn travel-tx [{:as db :keys [history]} travel-to]
  {:pre [(int? travel-to) (not (neg? travel-to))]}
  (let [[start end] (sort [travel-to (current-tx db)])
        datoms (into []
                     (comp (drop-while #(<= (:tx %) start))
                           (take (- end start))
                           (map :datoms))
                     (reverse history))]
    (assert (<= travel-to (last-tx db)) (str "Cannot move past the end of history. to: " travel-to ", last: " (last-tx db)))
    (case (compare travel-to (current-tx db))
      1 ;; forward
      {:txs [[:db/datoms (apply concat datoms)]]
       :options  {:travel-to travel-to}}
      -1 ;; backward
      {:txs [[:db/datoms-reverse (apply concat datoms)]]
       :options {:travel-to travel-to}}
      0 ;; ;; no-op
      nil)))