(ns re-db.protocols)

(defprotocol IConn
  (db [conn]))

(extend-type #?(:cljs cljs.core.Atom
                :clj clojure.lang.Atom)
  IConn
  (db [conn] @conn))

(defprotocol ITriple
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

(defn get-db [conn the-db] (or the-db (db conn)))

(defn transact
  ([conn txs] (-transact (db conn) conn txs))
  ([conn txs opts] (-transact (db conn) conn txs opts)))

(defn merge-schema [conn schema] (-merge-schema (db conn) conn schema))