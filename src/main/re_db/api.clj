(ns re-db.api
  (:refer-clojure :exclude [bound-fn]))

(def get-conn '(re-db.api/conn))
(def conn-var 're-db.api/*current-conn*)
(def branch-tx-log 're-db.core/*branch-tx-log*)

(defmacro with-conn
  "Evaluates body with *current-conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(binding [~conn-var (~'re-db.api/->conn ~conn)]
     ~@body))

(defmacro branch
  "Evaluates body with *current-conn* bound to a fork of `conn`. Returns a transaction."
  [conn & body]
  (let [[conn body] (if (or (symbol? conn) (map? conn))
                      [conn body]
                      [get-conn (cons conn body)])]
    `(binding [~conn-var (~'re-db.api/clone ~conn)
               ~branch-tx-log (atom [])]
       (let [db-before# @~conn-var
             val# (do ~@body)
             txs# @~branch-tx-log]
         (with-meta {:db-before db-before#
                     :db-after @~conn-var
                     :datoms (into [] (mapcat :datoms) txs#)}
                    {:value val#})))))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# ~get-conn]
     (fn [& args#]
       (binding [~conn-var conn#]
         (apply f# args#)))))