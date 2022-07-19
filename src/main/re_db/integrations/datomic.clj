(ns re-db.integrations.datomic
  (:require [datomic.api :as d]
            [re-db.protocols :as rp]
            [re-db.util :as u])
  (:import [datomic.peer LocalConnection Connection]
           [datomic.db Db]
           [datomic Datom]))

(defn- v [^Datom d] (.-v d))

(extend-type Db
  rp/ITriple
  (eav
    ([db e a]
     (let [datoms (d/datoms db :eavt e a)]
       (if (rp/many? db a)
         (mapv v datoms)
         (some-> (first datoms) v))))
    ([db e] (rp/datoms->map (fn [db a] (d/ident db a)) db (d/datoms db :eavt e))))
  (ave [db a v]
    (if (:db/index (d/entity db a))
      (into #{} (map :e) (d/datoms db :avet a v))
      (d/q
       '[:find [?e ...]
         :where [?e ?a ?v]
         :in $ ?a ?v]
       db a v)))
  (ae [db a] (into #{} (map :e) (d/datoms db :aevt a)))
  (datom-a [db a] (d/entid db a))
  (get-schema [db a] (d/attribute db a))
  (id->ident [db e] (d/ident db e))
  (ref?
    ([db a] (= :db.type/ref (:value-type (d/attribute db a))))
    ([db a schema] (= :db.type/ref (:value-type schema))))
  (unique?
    ([db a] (:unique (d/attribute db a)))
    ([db a schema] (:unique schema)))
  (many?
    ([db a] (= :db.cardinality/many (:cardinality (d/attribute db a))))
    ([db a schema] (= :db.cardinality/many (:cardinality schema))))
  (-transact
    ([db conn txs] @(d/transact conn txs))
    ([db conn txs opts] @(d/transact conn txs opts)))
  (-merge-schema [db conn schema]
    @(d/transact conn (mapcat (fn [[ident m]]
                             (let [id (d/tempid :db.part/db)]
                               (into [[:db/add id :db/ident ident]]
                                     (for [[k v] m]
                                       [:db/add id k v]))))
                           schema)))
  (doto-report-triples [db f report] (doseq [[e a v] (:tx-data report)] (f e a v))))

(extend-type LocalConnection
  rp/IConn
  (db [conn] (d/db conn)))

(extend-type Connection
  rp/IConn
  (db [conn] (d/db conn)))