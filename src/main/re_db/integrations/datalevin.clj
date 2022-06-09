(ns re-db.integrations.datalevin
  (:require [datalevin.core :as d]
            [re-db.protocols :as rp]))

'(defprotocol ITriple
  (eav [db e a] [db e])
  (ave [db a v])
  (ae [db a])
  (datom-a [db a] "Return attribute as represented in a datom")
  (get-schema [db a])
  (ref? [db a] [db a schema])
  (unique? [db a] [db a schema])
  (many? [db a] [db a schema])

  (doto-report-triples [db f report])
  ;; fns that operate on a connection, but dispatch on db-type
  (-transact [db conn txs] [db conn txs options])
  (-merge-schema [db conn schema]))

(comment
 (clojure.java.shell/sh "arch")

 (def conn (d/get-conn "/tmp/datalevin/mydb" {}))
 (:require '[clojure.java.shell :as sh])


 )