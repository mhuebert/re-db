(ns re-db.integrations.datahike
  (:require [datahike.api :as d]
            datahike.datom
            datahike.db
            [re-db.triplestore :as ts])
  (:import datahike.db.DB
           datahike.datom.Datom))

(defn- v [^datahike.datom.Datom d] (.-v d))

(extend-type datahike.db.DB
  ts/ITripleStore
  (eav
    ([db e a]
     (let [datoms (d/datoms db :eavt e a)]
       (if (ts/many? db a)
         (mapv v datoms)
         (some-> (first datoms) v))))
    ([db e] (ts/datoms->map db (d/datoms db :eavt e))))
  (ave [db a v] (into #{} (map :e) (d/datoms db :avet a v)))
  (ae [db a] (into #{} (map :e) (d/datoms db :aevt a)))
  (datom-a [db a] a)
  (get-schema [db a] (d/entity db a))
  (id->ident [db e] (or (ts/eav db e :db/ident) e))
  (ref?
    ([this a] (ts/ref? this a (ts/get-schema this a)))
    ([this a schema] (= :db.type/ref (:db/valueType schema))))
  (unique?
    ([this a] (ts/unique? this a (ts/get-schema this a)))
    ([this a schema] (:db/unique schema)))
  (many?
    ([this a] (ts/many? this a (ts/get-schema this a)))
    ([this a schema] (= :db.cardinality/many (:db/cardinality schema))))
  (report-triples [this report f]
    (doseq [^datahike.datom.Datom datom (:tx-data report)]
      (f (.-e datom) (.-a datom) (.-v datom))))
  (-transact
    ([db conn txs] (d/transact conn txs))
    ([db conn txs opts] (d/transact conn txs opts)))
  (-merge-schema [db conn schema]
    (d/transact conn (mapv (fn [[ident m]] (assoc m :db/ident ident)) schema))))