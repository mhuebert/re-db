(ns re-db.api
  "Reactive API for re-db (with a default, dynamically bound connection)."
  (:refer-clojure :exclude [get contains? select-keys namespace bound-fn])
  (:require [re-db.in-memory :as mem]
            [re-db.integrations.in-memory]
            [re-db.triplestore :as ts]
            [re-db.reactive :as r :refer [defpartial]]
            [re-db.read :as read])
  #?(:cljs (:require-macros re-db.api)))

(defonce ^:dynamic *conn* (mem/create-conn))

(defmacro with-conn
  "Evaluates body with *conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(binding [*conn* (mem/->conn ~conn)] (assert *conn*) ~@body))

(defn bind
  "Binds a conn for evaluation of function `f`"
  ([f]
   (bind *conn* f))
  ([conn f]
   (fn [& args]
     (binding [*conn* conn]
       (apply f args)))))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# *conn*]
     (fn [& args#]
       (binding [*conn* conn#]
         (apply f# args#)))))

(defmacro bound-reaction
  "Returns a reaction which evaluates `body` with *conn* bound to the provided value."
  [conn & body]
  (let [[options body] (r/parse-reaction-args &form body)]
    `(let [conn# ~conn]
       (r/reaction ~options
         (with-conn conn# ~@body)))))

(defn entity [id] (read/entity *conn* id))

(defpartial get "Read entity or attribute reactively"
  {:f '(read/get *conn* _)}
  ([e])
  ([e a])
  ([e a not-found]))

(defn where [clauses] (read/where *conn* clauses))

(defpartial pull {:f '(read/pull *conn* _)}
  ([pull-expr])
  ([pull-expr e])
  ([options pull-expr e]))

(defn partial-pull [options]
  (fn pull-fn
    ([pull-expr]
     (fn [e] (pull-fn pull-expr e)))
    ([pull-expr e]
     (pull options pull-expr e))
    ([options-2 pull-expr e]
     (pull (merge options options-2) pull-expr e))))

(defpartial transact! {:f '(read/transact! *conn* _)}
  ([txs])
  ([txs options]))

(defpartial merge-schema! {:f '(ts/-merge-schema (ts/db *conn*) *conn* _)}
  [schema])

(defn conn [] *conn*)
(defn touch [entity] (read/touch entity))

(comment
 ;; maybe add this?
 (defn retraction
   "Returns a retraction transaction for the given entity or entity/attribute pair"
   ([entity]
    [:db/retractEntity (:db/id entity entity)])
   ([entity a]
    [:db/retract (:db/id entity entity) a])))