(ns re-db.integrations.datomic
  (:require [clojure.core.async :as a]
            [datomic.api :as d]
            [re-db.patterns :as patterns]
            [re-db.protocols :as rp])
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
               (#'patterns/invalidate-report-datoms! conn report datomic-doto-triples!)
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

(defn ea [db e a]
  (get (d/entity db e) a))

(defn a [db a]
  (d/q
   '[:find [?e ...]
     :where [?e ?a]
     :in $ ?a]
   db a))

(defn av [db a v]
  (d/q
   '[:find [?e ...]
     :where [?e ?a ?v]
     :in $ ?a ?v]
   db a v))

(extend-type Db
  rp/ITriple
  (eav [db e a] (get (d/entity db e) a))
  (ave [db a v] (d/q
                 '[:find [?e ...]
                   :where [?e ?a ?v]
                   :in $ ?a ?v]
                 db a v))
  (vae [db v])
  (ae [db a])
  (internal-e [db e] (:db/id (d/entity db e)))
  (e-map [db e] (d/touch (d/entity db e)))
  (get-schema [db a] (d/entity db a))
  (ref?
    ([this a] (rp/ref? this a (rp/get-schema this a)))
    ([this a schema] (= :db.type/ref (:db/valueType schema))))
  (unique?
    ([this a] (rp/unique? this a (rp/get-schema this a)))
    ([this a schema] (:db/unique schema)))
  (many?
    ([this a] (rp/many? this a (rp/get-schema this a)))
    ([this a schema] (= :db.cardinality/many (:db/cardinality schema)))))

(extend-type LocalConnection
  rp/ITriple
  (db [conn] (d/db conn))
  (eav [conn e a] (rp/eav (rp/db conn) e a))
  (ave [conn a v] (rp/ave (rp/db conn) a v))
  (vae [conn v])
  (ae [conn a])
  (e [conn e] (rp/internal-e (rp/db conn) e))
  (e-map [conn e] (rp/as-map (rp/db conn) e))
  (get-schema [conn a] (rp/get-schema (rp/db conn) a))
  (ref?
    ([this a] (rp/ref? (rp/db this) a))
    ([this a schema] (rp/ref? (rp/db this) a schema)))
  (unique?
    ([this a] (rp/unique? (rp/db this) a))
    ([this a schema] (rp/unique? (rp/db this) a schema)))
  (many?
    ([this a] (rp/many? (rp/db this) a))
    ([this a schema] (rp/many? (rp/db this) a schema))))