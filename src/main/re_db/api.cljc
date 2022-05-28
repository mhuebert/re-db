(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace bound-fn])
  (:require [re-db.in-memory :as mem]
            re-db.integrations.in-memory
            [re-db.macros :as m :refer [defpartial]]
            [re-db.protocols :as rp]
            [re-db.read :as read :refer [*conn*]]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.api)))

(def create-conn mem/create-conn)

(defmacro with-conn
  "Evaluates body with *conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(binding [*conn* (mem/->conn ~conn)] (assert *conn*) ~@body))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# *conn*]
     (fn [& args#]
       (binding [*conn* conn#]
         (apply f# args#)))))

(defn entity [id] (read/entity id))

(defn get
  "Read entity or attribute reactively"
  ([e]
   (some-> (read/resolve-e e) read/eav))
  ([e a]
   (some-> (read/resolve-e e) (read/eav a)))
  ([e a not-found]
   (util/some-or (get e a) not-found)))

(defn where [clauses] (read/where clauses))

(m/defpartial pull {:f '(read/pull _)}
  ([pull-expr])
  ([pull-expr id]))

(m/defpartial pull-entities {:f '(read/pull-entities _)}
  ([pull-expr])
  ([pull-expr id]))

(m/defpartial transact! {:f '(->> (rp/transact *conn* _)
                                  (read/handle-report! *conn*))}
  ([txs])
  ([txs opts]))

(m/defpartial merge-schema! {:f '(rp/-merge-schema (rp/db *conn*) *conn* _)}
  [schema])

(defn bind
  "Binds a conn for evaluation of function `f`"
  ([f]
   (bind *conn* f))
  ([conn f]
   (fn [& args]
     (binding [*conn* conn]
       (apply f args)))))

(defn conn [] *conn*)
(defn touch [entity] @entity)