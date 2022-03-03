(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace clone bound-fn])
  (:require [re-db.core :as d]
            [re-db.read :as read]
            [re-db.macros :as m])
  #?(:cljs (:require-macros re-db.api)))

(defmacro with-conn
  "Evaluates body with *current-conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(binding [~'re-db.api/*current-conn* (~'re-db.api/->conn ~conn)]
     ~@body))

(defmacro branch
  "Evaluates body with *current-conn* bound to a fork of `conn`. Returns a transaction."
  [conn & body]
  (let [[conn body] (if (or (symbol? conn) (map? conn))
                      [conn body]
                      [('re-db.api/conn) (cons conn body)])]
    `(binding [~'re-db.api/*current-conn* (~'re-db.api/clone ~conn)
               ~'re-db.core/*branch-tx-log* (atom [])]
       (let [db-before# @~'re-db.api/*current-conn*
             val# (do ~@body)
             txs# @~'re-db.core/*branch-tx-log*]
         (with-meta {:db-before db-before#
                     :db-after @~'re-db.api/*current-conn*
                     :datoms (into [] (mapcat :datoms) txs#)}
                    {:value val#})))))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# (~'re-db.api/conn)]
     (fn [& args#]
       (binding [~'re-db.api/*current-conn* conn#]
         (apply f# args#)))))

(defonce ^:dynamic *current-conn* (read/create-conn {}))

(defn conn [] *current-conn*)

(def create-conn read/create-conn)

(defn ->conn
  "Accepts a conn or a schema, returns conn"
  [conn-or-schema]
  (if (map? conn-or-schema)
    (read/create-conn conn-or-schema)
    conn-or-schema))

(defn clone
  "Creates a copy of conn (without listeners)"
  [conn]
  (read/listen-conn (atom (dissoc @conn :cached-readers))))

(m/defpartial entity {:f '(read/entity (conn) _)}
  [id])

(m/defpartial get {:f '(read/get (conn) _)}
  ([id])
  ([id attr])
  ([id attr not-found]))

(m/defpartial ids-where {:f '(read/ids-where (conn) _)}
  [qs])

(m/defpartial where {:f '(read/where (conn) _)}
  [qs])

(m/defpartial touch {:f '(read/touch _)}
  ([entity])
  ([entity entity-refs?]))

(m/defpartial pull {:f '(read/pull (conn) _)}
  [id pull])

(m/defpartial transact! {:f '(d/transact! (conn) _)}
  ([txs])
  ([txs opts]))

(m/defpartial listen {:f '(read/listen (conn) _)}
  [callback])

(def merge-schema! (partial d/merge-schema! (conn)))

(defn bind
  "Binds a conn for evaluation of function `f`"
  ([f]
   (bind (conn) f))
  ([conn f]
   (fn [& args]
     (binding [*current-conn* conn]
       (apply f args)))))