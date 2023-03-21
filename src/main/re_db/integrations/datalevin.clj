(ns re-db.integrations.datalevin
  (:require [datalevin.core :as d]
            datalevin.datom
            [datalevin.db :as db]
            [re-db.triplestore :as ts]
            [re-db.schema :as s])
  (:import datalevin.db.DB
           datalevin.datom.Datom))

(defn- v [^datalevin.datom.Datom d] (.-v d))

(extend-type datalevin.db.DB
  ts/ITripleStore
  (eav
    ([db e a]
     (let [datoms (d/datoms db :eavt e a)]
       (if (ts/many? db a)
         (mapv v datoms)
         (some-> (first datoms) v))))
    ([db e] (ts/datoms->map db (d/datoms db :eavt e))))
  (ave [db a v] (into #{} (map (fn [^datalevin.datom.Datom d] (.-e d))) (d/datoms db :ave a v)))
  (ae [db a] (into #{} (map (fn [^datalevin.datom.Datom d] (.-e d))) (d/datoms db :ave a)))
  (datom-a [db a] a)
  (get-schema [db a] ((db/-schema db) a))
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
 (ts/get-schema @conn :a)

 (:require '[clojure.java.shell :as sh])

 )