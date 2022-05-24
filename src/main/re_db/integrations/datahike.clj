(ns re-db.integrations.datahike
  (:require [datahike.api :as dh]
            [datahike.core :as dh.core]
            datahike.db
            datahike.datom
            [re-db.protocols :as rp])
  (:import datahike.db.DB
           datahike.datom.Datom))

(extend-type datahike.db.DB
  rp/ITriple
  (eav
    ([db e a] (get (dh/entity db e) a))
    ([db e] (dh/pull db '[*] e)))
  (ave [db a v] (into #{} (map :e) (dh/datoms db :avet a v)))
  (ae [db a] (into #{} (map :e) (dh/datoms db :aevt a)))
  (datom-a [db a] a)
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
    (doseq [^datahike.datom.Datom datom (:tx-data report)]
      (f (.-e datom) (.-a datom) (.-v datom))))
  (-transact
    ([db conn txs] (dh/transact conn txs))
    ([db conn txs opts] (dh/transact conn txs opts)))
  (-merge-schema [db conn schema]
    (dh/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema))))