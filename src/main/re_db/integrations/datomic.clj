(ns re-db.integrations.datomic
  (:require [clojure.core.async :as a]
            [datomic.api :as d]
            [re-db.patterns :as patterns]
            [re-db.protocols :as rp]
            [re-db.util :as util])
  (:import [datomic.peer LocalConnection]
           [datomic.db Db]))

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

 (defn readable-patterns [consumer db]
   (let [patterns (re-db.query/get-patterns consumer)]
     [(mapv (partial readable-pattern db) patterns) (vec patterns)])))

(defn report-tx [{:keys [tx-data db-after]}]
  (d/entity db-after (-> tx-data first :tx)))

(defn init-locking [conn]
  @(d/transact conn [{:db/ident ::lock
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one}]))

(defonce !conn-state (atom {}))

(defn handle-lock [conn report]
  (let [lock (::lock (report-tx report))]
    (when-let [promise (get-in @!conn-state [conn :locks lock])]
      (swap! !conn-state update-in [conn :locks] dissoc lock)
      (deliver promise true))))

(defn blocking-transact
  "Transacts txs, blocks until tx-report has been handled."
  [conn txs]
  (let [lock (str (gensym "lock"))
        p (promise)
        _ (swap! !conn-state assoc-in [conn :locks lock] p)
        tx-report @(d/transact conn (conj txs [:db/add "datomic.tx" ::lock lock]))]
    @p
    tx-report))

(defn datomic-doto-triples! [handle-triple! tx-report]
  (doseq [[e a v] (:tx-data tx-report)] (handle-triple! e a v)))

(defn init-conn!
  ([conn] (init-conn! conn (get-tx-chan conn)))
  ([conn tx-chan]
   (when-not (contains? @!conn-state conn)
     (init-locking conn)
     (let [!continue (atom true)]
       (a/go
         (try
           (while @!continue
             (let [report (a/<! tx-chan)]
               (#'patterns/handle-report! conn report datomic-doto-triples!)
               (handle-lock conn report)))
           (catch Exception e (println :error-handling-tx-report e))))
       (swap! !conn-state update conn assoc
              :stop #(do (reset! !continue false)
                         (a/close! tx-chan)
                         (swap! !conn-state dissoc conn)))))
   conn))

(defn dispose-conn! [conn]
  (when-let [stop (get-in @!conn-state [conn :stop])]
    (stop)))

(defn -eav
  ([db e a]
   (get (d/entity db e) a))
  ([db e]
   (into {:db/id e} (d/touch (d/entity db e)))))

(defn -ae [db a]
  (d/q
   '[:find [?e ...]
     :where [?e ?a]
     :in $ ?a]
   db a))

(defn -ave [db a v]
  (d/q
   '[:find [?e ...]
     :where [?e ?a ?v]
     :in $ ?a ?v]
   db a v))

(extend-type Db
  rp/ITriple
  (eav
    ([db e a] (-eav db e a))
    ([db e] (-eav db e)))
  (ave [db a v] (-ave db a v))
  (ae [db a] (-ae db a))
  (internal-e [db e] (d/entid db e))
  (get-schema [db a] (d/entity db a))
  (ref?
    ([db a] (rp/ref? db a (rp/get-schema db a)))
    ([db a schema] (= :db.type/ref (:db/valueType schema))))
  (unique?
    ([db a] (rp/unique? db a (rp/get-schema db a)))
    ([db a schema] (:db/unique schema)))
  (many?
    ([db a] (rp/many? db a (rp/get-schema db a)))
    ([db a schema] (= :db.cardinality/many (:db/cardinality schema))))
  (transact
    ([db conn txs] (d/transact conn txs))
    ([db conn txs opts] (d/transact conn txs opts)))
  (merge-schema [db conn schema]
    (d/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema)))
  (doto-report-triples [db f report] (datomic-doto-triples! f report)))

(extend-type LocalConnection
  rp/IConn
  (db [conn] (d/db conn)))