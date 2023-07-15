(ns re-db.triplestore
  "Triplestore abstraction"
  (:require [re-db.util :as u]))

(defprotocol IConn
  (db [conn]))

(extend-protocol IConn
  #?(:cljs cljs.core.Atom
     :clj  clojure.lang.Atom)
  (db [conn] @conn)
  #?(:cljs default
     :clj java.lang.Object)
  (db [_] nil))

(defprotocol ITripleStore
  (eav
    [db e]
    [db e a-schema a]  "Returns a value or a map of attributes to values")
  (ave [db a-schema a v] "Returns a list of entity ids")
  (ae [db a-schema a] "Returns a list of entity ids for entities containing a")
  (datom-a [db a] "Return attribute as represented in a datom")
  (-get-schema [db a])
  (ref? [db a-schema])
  (unique? [db a-schema])
  (many? [db a-schema])
  (component? [db a-schema])
  (id->ident [db e] "Returns :db/ident if it exists for e")

  (report-triples [db report f] "Calls (f e a v) for each triple in a tx-report")
  ;; fns that operate on a connection, but dispatch on db-type
  (-transact [db conn txs] [db conn txs options])
  (-merge-schema [db conn schema]))

(defn get-schema [db a] (-get-schema db (cond-> a (keyword? a) u/forward-attr)))

(defn triples
  "Returns triples"
  [db report]
  (let [out (volatile! [])]
    (report-triples db report (fn [e a v] (vswap! out conj [e a v])))
    @out))

(defn get-db [conn the-db] (or the-db (db conn)))

(defn transact
  ([conn txs] (-transact (db conn) conn txs))
  ([conn txs opts] (-transact (db conn) conn txs opts)))

(defn merge-schema [conn schema] (-merge-schema (db conn) conn schema))

(defn datoms->map
  ([db datoms] (datoms->map (fn [db a] a) db datoms))
  ([id-ident db datoms]
   (let [many? (memoize (fn [a] (many? db (get-schema db a))))]
     (reduce (fn [out [_ a v]]
               (let [attr (id-ident db a)]
                 (if (many? a)
                   (update out attr (fnil conj #{}) v)
                   (assoc out attr v)))) {} datoms))))