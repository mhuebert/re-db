(ns re-db.integrations.datahike
  (:require [datahike.api :as d]
            datahike.datom
            datahike.db
            [re-db.protocols :as rp])
  (:import datahike.db.DB
           datahike.datom.Datom))

(defn- v [^datahike.datom.Datom d] (.-v d))

(extend-type datahike.db.DB
  rp/ITriple
  (eav
    ([db e a]
     (let [datoms (d/datoms db :eavt e a)]
       (if (rp/many? db a)
         (mapv v datoms)
         (some-> (first datoms) v))))
    ([db e] (d/pull db '[*] e)))
  (ave [db a v] (into #{} (map :e) (d/datoms db :avet a v)))
  (ae [db a] (into #{} (map :e) (d/datoms db :aevt a)))
  (datom-a [db a] a)
  (get-schema [db a] (d/entity db a))
  (id->ident [db e] (or (rp/eav db e :db/ident) e))
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
    ([db conn txs] (d/transact conn txs))
    ([db conn txs opts] (d/transact conn txs opts)))
  (-merge-schema [db conn schema]
    (d/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema))))