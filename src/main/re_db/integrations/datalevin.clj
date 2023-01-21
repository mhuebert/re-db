(ns re-db.integrations.datalevin
  (:require [datalevin.core :as d]
            datalevin.datom
            [datalevin.db :as db]
            [re-db.protocols :as rp]
            [re-db.schema :as s])
  (:import datalevin.db.DB
           datalevin.datom.Datom))

(defn- v [^datalevin.datom.Datom d] (.-v d))

(extend-type datalevin.db.DB
  rp/ITriple
  (eav
    ([db e a]
     (let [datoms (d/datoms db :eavt e a)]
       (if (rp/many? db a)
         (mapv v datoms)
         (some-> (first datoms) v))))
    ([db e] (rp/datoms->map db (d/datoms db :eavt e))))
  (ave [db a v] (into #{} (map (fn [^datalevin.datom.Datom d] (.-e d))) (d/datoms db :ave a v)))
  (ae [db a] (into #{} (map (fn [^datalevin.datom.Datom d] (.-e d))) (d/datoms db :ave a)))
  (datom-a [db a] a)
  (get-schema [db a] ((db/-schema db) a))
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
    (doseq [^datalevin.datom.Datom datom (:tx-data report)]
      (f (.-e datom) (.-a datom) (.-v datom))))
  (-transact
    ([db conn txs] (d/transact! conn txs))
    ([db conn txs opts] (d/transact! conn txs opts)))
  (-merge-schema [db conn schema]
    (d/update-schema conn schema)))

(comment
 (clojure.java.shell/sh "arch")

 (def conn (d/get-conn "/tmp/datalevin/mydb" {}))
 (rp/get-schema @conn :a)

 (:require '[clojure.java.shell :as sh])

 )