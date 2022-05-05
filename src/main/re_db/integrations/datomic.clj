(ns re-db.integrations.datomic
  (:require [clojure.core.async :as a]
            [datomic.api :as d]
            [re-db.reactive.query :as q]))

(defonce !threads (atom {}))

(defn tx-report-thread [conn]
  (or (@!threads conn)
      (let [chan (a/chan)
            continue? (atom true)
            mult (a/mult chan)
            change-queue (d/tx-report-queue conn)
            thread (Thread. (fn [] (loop []
                                     (try
                                       (a/put! chan (.take change-queue))
                                       (catch InterruptedException _e
                                         (swap! !threads dissoc conn)
                                         (reset! continue? false)))
                                     (when @continue?
                                       (recur)))))
            state {:mult mult :thread thread :conn conn}]
        (swap! !threads assoc conn state)
        (.start thread)
        state)))

(defn stop-tx-report-thread! [{:keys [thread conn]}]
  (swap! !threads dissoc conn)
  (.interrupt thread))

(defn get-tx-chan
  "takes a context and returns a channel which receives all transactions"
  [conn]
  (-> (tx-report-thread conn)
      :mult
      (a/tap (a/chan 1000))))

(comment

 ;; dev helpers

 (defn ident-name [db ident]
   (if (number? ident)
     (get (d/entity db ident) :db/ident ident)
     ident))

 (defn readable-pattern [db pattern]
   (mapv #(ident-name db %) pattern))

 (defmacro inspect-patterns [db & body]
   `(let [db# ~db]
      (->> (q/capture-patterns db# ~@body)
           second
           (mapv (partial readable-pattern db#)))))

 (defn readable-patterns [consumer db]
   (let [patterns (re-db.reactive.query/get-patterns consumer)]
     [(mapv (partial readable-pattern db) patterns) (vec patterns)])))

(defn report-tx [{:keys [tx-data db-after]}]
  (d/entity db-after (-> tx-data first :tx)))

(defn init-locking [conn]
  @(d/transact conn [{:db/ident ::lock
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one}]))

(defonce !state (atom {}))

(defn handle-lock [conn report]
  (let [lock (::lock (report-tx report))]
    (when-let [promise (get-in @!state [conn :locks lock])]
      (swap! !state update-in [conn :locks] dissoc lock)
      (deliver promise true))))

(defn blocking-transact
  "Transacts txs, blocks until tx-report has been handled."
  [conn txs]
  (let [lock (str (gensym "lock"))
        p (promise)
        _ (swap! !state assoc-in [conn :locks lock] p)
        tx-report @(d/transact conn (conj txs [:db/add "datomic.tx" ::lock lock]))]
    @p
    tx-report))

(defn init-conn!
  ([conn] (init-conn! conn (get-tx-chan conn)))
  ([conn tx-chan]
   (when-not (contains? @!state conn)
     (init-locking conn)
     (let [!continue (atom true)]
       (a/go
         (try
           (while @!continue
             (let [report (a/<! tx-chan)
                   listeners (get-in @q/!state [conn :pattern-listeners])]
               (#'q/handle-datoms! listeners (:tx-data report))
               (handle-lock conn report)))
           (catch Exception e (println :error-handling-tx-report e))))
       (swap! !state update conn assoc
              :stop #(do (reset! !continue false)
                         (a/close! tx-chan)
                         (swap! !state dissoc conn)))))
   conn))

(defn dispose-conn! [conn]
  (when-let [stop (get-in @!state [conn :stop])]
    (stop)))