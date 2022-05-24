(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace bound-fn])
  (:require [re-db.in-memory :as mem]
            [re-db.integrations.in-memory]
            [re-db.protocols :as rp]
            [re-db.patterns :as patterns :refer [*conn*]]
            [re-db.pull :as pull]
            [re-db.entity :as entity]
            [re-db.query :as query]
            [re-db.where :as where]
            [re-db.macros :as m :refer [defpartial]]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.api)))

(def create-conn mem/create-conn)

(defmacro with-conn
  "Evaluates body with *conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(patterns/with-conn ~conn ~@body))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# *conn*]
     (fn [& args#]
       (binding [*conn* conn#]
         (apply f# args#)))))

(defn entity [id] (entity/entity id))

(defn get
  "Read entity or attribute reactively"
  ([e]
   (some-> (patterns/resolve-e e) patterns/eav))
  ([e a]
   (some-> (patterns/resolve-e e) (patterns/eav a)))
  ([e a not-found]
   (util/some-or (get e a) not-found)))

(defn where [clauses] (where/where clauses))

(m/defpartial pull {:f '(pull/pull _)}
  ([pull-expr])
  ([id pull-expr]))

(m/defpartial pull-entities {:f '(pull/pull-entities _)}
  ([pull-expr])
  ([id pull-expr]))

(m/defpartial transact! {:f '(->> (rp/transact *conn* _)
                                  (patterns/handle-report! *conn*))}
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

(defmacro branch
  "Evaluates body with *conn* bound to a fork of `conn`. Returns a transaction."
  [conn & body]
  (let [[conn body] (if (or (symbol? conn) (map? conn))
                      [conn body]
                      ['re-db.patterns/*conn* (cons conn body)])]
    `(binding [*conn* (patterns/clone ~conn)
               ~'re-db.in-memory/*branch-tx-log* (atom [])]
       (let [db-before# @*conn*
             val# (do ~@body)
             txs# @~'re-db.in-memory/*branch-tx-log*]
         (with-meta {:db-before db-before#
                     :db-after @*conn*
                     :datoms (into [] (mapcat :datoms) txs#)}
                    {:value val#})))))

(defn conn [] *conn*)
(defn touch [entity] @entity)