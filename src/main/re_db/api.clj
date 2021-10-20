(ns re-db.api)

(def current-conn 're-db.api/*current-conn*)
(def branch-tx-log 're-db.core/*branch-tx-log*)

(defmacro with-conn
  "Evaluates body with *current-conn* bound to `conn`, which may be a connection or a schema"
  [conn & body]
  `(binding [~current-conn (~'re-db.api/conn* ~conn)]
     ~@body))

(defmacro branch
  "Evaluates body with *current-conn* bound to a fork of `conn`. Returns a transaction."
  [conn & body]
  (let [[conn body] (if (or (symbol? conn) (map? conn))
                      [conn body]
                      [current-conn (cons conn body)])]
    `(binding [~current-conn (~'re-db.api/clone ~conn)
               ~branch-tx-log (atom [])]
       (let [db-before# @~current-conn
             val# (do ~@body)
             txs# @~branch-tx-log]
         (with-meta {:db-before db-before#
                     :db-after @~current-conn
                     :datoms (into [] (mapcat :datoms) txs#)}
                    {:value val#})))))

(defmacro bound-fn
  "Define an anonymous function where *conn* is bound at definition-time"
  [& body]
  `(let [f# (~'fn ~@body)
         conn# ~current-conn]
     (fn [& args#]
       (binding [~current-conn conn#]
         (apply f# args#)))))