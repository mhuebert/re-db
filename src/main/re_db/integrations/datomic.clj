(ns re-db.integrations.datomic
  (:require [datomic.api :as d]
            [re-db.protocols :as rp])
  (:import [datomic.peer LocalConnection Connection]
           [datomic.db Db]))

(extend-type Db
  rp/ITriple
  (eav
    ([db e a] (get (d/entity db e) a))
    ([db e] (d/pull db '[*] e)))
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
  (-transact
    ([db conn txs] @(d/transact conn txs))
    ([db conn txs opts] @(d/transact conn txs opts)))
  (-merge-schema [db conn schema]
    @(d/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema)))
  (doto-report-triples [db f report] (doseq [[e a v] (:tx-data report)] (f e a v))))

(extend-type LocalConnection
  rp/IConn
  (db [conn] (d/db conn)))

(extend-type Connection
  rp/IConn
  (db [conn] (d/db conn)))