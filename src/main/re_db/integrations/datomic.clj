(ns re-db.integrations.datomic
  (:require [taoensso.tufte :as tufte :refer [defnp p profiled profile]]
            [datomic.api :as d]
            [re-db.triplestore :as ts]
            [re-db.util :as u])
  (:import [datomic.peer LocalConnection Connection]
           [datomic.db Db]
           [datomic Datom]))

(defn- datom-v [^Datom d] (.-v d))

(defn ave-index? [attribute]
  (or (:indexed attribute)
      (:unique attribute)))

(extend-type Db
  ts/ITripleStore
  (eav
    ([db a-schema e a] (get (d/entity db e) a))
    ([db e]
     (ts/datoms->map (fn [db a] (d/ident db a)) db (d/datoms db :eavt e))))
  (ave [db a-schema a v]
    (if (ave-index? a-schema)
      (map :e (d/datoms db :avet a v))
      (d/q
       '[:find [?e ...]
         :where [?e ?a ?v]
         :in $ ?a ?v]
       db a v)))
  (ae [db a-schema a] (map :e (d/datoms db :aevt a)))
  (datom-a [db a] (d/entid db a))
  (-get-schema [db a] (d/attribute db a))
  (id->ident [db e] (d/ident db e))
  (component? [this schema] (:db/isComponent schema))
  (ref? [db schema] (= :db.type/ref (:value-type schema)))
  (unique? [db schema] (:unique schema))
  (many? [db schema] (= :db.cardinality/many (:cardinality schema)))
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
  (report-triples [db report f] (doseq [[e a v] (:tx-data report)] (f e a v))))

(extend-type LocalConnection
  ts/IConn
  (db [conn] (d/db conn)))

(extend-type Connection
  ts/IConn
  (db [conn] (d/db conn)))