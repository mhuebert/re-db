(ns re-db.integrations.datahike
  (:require [datahike.api :as dh]
            [datahike.core :as dh.core]
            datahike.db
            [re-db.protocols :as rp])
  (:import [datahike.db DB]))

(extend-type datahike.db.DB
  rp/ITriple
  (eav
    ([db e a] (get (dh/entity db e) a))
    ([db e] (into {:db/id e} (dh/entity db e))))
  (ave [db a v] (into #{} (map :e) (dh/datoms db :avet a v)))
  (ae [db a] (into #{} (map :e) (dh/datoms db :aevt a)))
  (internal-e [db e] (dh.core/entid db e))
  (get-schema [db a] (dh/entity db a))
  (ref?
    ([this a] (rp/ref? this a (rp/get-schema this a)))
    ([this a schema] (= :db.type/ref (:db/valueType schema))))
  (unique?
    ([this a] (rp/unique? this a (rp/get-schema this a)))
    ([this a schema] (:db/unique schema)))
  (many?
    ([this a] (rp/many? this a (rp/get-schema this a)))
    ([this a schema] (= :db.cardinality/many (:db/cardinality schema))))
  (doto-report-triples [this f report]
    (doseq [[e a v] (:tx-data report)] (f e a v)))
  (transact
    ([db conn txs] (dh/transact conn txs))
    ([db conn txs opts] (dh/transact conn txs opts)))
  (merge-schema [db conn schema]
    (dh/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema))))