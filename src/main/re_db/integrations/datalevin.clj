(ns re-db.integrations.datalevin
  (:require [taoensso.tufte :as tufte :refer [defnp p profiled profile]]
            [datalevin.core :as d]
            datalevin.datom
            [datalevin.db :as db]
            [re-db.triplestore :as ts]
            [re-db.schema :as s])
  (:import datalevin.db.DB
           datalevin.datom.Datom))

(defn- datom-v [^datalevin.datom.Datom d] (.-v d))
(defn- datom-e [^datalevin.datom.Datom d] (.-e d))


(extend-type datalevin.db.DB
  ts/ITripleStore
  (eav
    ([db a-schema e a]
     (when-let [e (db/entid db e)]
       (let [datoms (d/datoms db :eavt e a)]
         (if (ts/many? db a-schema)
           (map datom-v datoms)
           (:v (first datoms))))))
    ([db e] (when-let [e (db/entid db e)]
              (ts/datoms->map db (d/datoms db :eavt e)))))
  (ave [db a-schema a v] (map datom-e (d/datoms db :ave a v)))
  (ae [db a-schema a] (map datom-e (d/datoms db :ave a)))
  (datom-a [db a] a)
  (-get-schema [db a] ((db/-schema db) a))
  (id->ident [db e] (or (ts/eav db (ts/get-schema db :db/ident) e :db/ident)
                        e))
  (ref? [this schema] (= :db.type/ref (:db/valueType schema)))
  (unique? [this schema] (:db/unique schema))
  (many? [this schema] (= :db.cardinality/many (:db/cardinality schema)))
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