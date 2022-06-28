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
  (id->ident [db e] "Returns :db/ident if it exists for e")

  (doto-report-triples [db f report])
  ;; fns that operate on a connection, but dispatch on db-type
  (-transact [db conn txs] [db conn txs options])
  (-merge-schema [db conn schema]))

(defn get-db [conn the-db] (or the-db (db conn)))

(defn transact
  ([conn txs] (-transact (db conn) conn txs))
  ([conn txs opts] (-transact (db conn) conn txs opts)))

(defn merge-schema [conn schema] (-merge-schema (db conn) conn schema))

(defn datoms->map
  ([db datoms] (datoms->map (fn [db a] a) db datoms))
  ([id-ident db datoms]
   (let [many? (memoize (fn [a] (many? db a)))]
     (reduce (fn [out [_ a v]]
               (let [attr (id-ident db a)]
                 (if (many? a)
                   (update out attr (fnil conj #{}) v)
                   (assoc out attr v)))) {} datoms))))