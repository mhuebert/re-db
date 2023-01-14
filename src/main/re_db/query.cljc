(ns re-db.query
  (:require [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.subscriptions :as subs])
  #?(:cljs (:require-macros re-db.query)))

(defmacro try-value [& body]
  `(try {:value (do ~@body)}
       (catch ~(if (:ns &env) 'js/Error 'Exception) e#
         {:error e#})))

(defmacro reaction
  "Returns a reaction which evaluates `body`, in which calls using `re-db.api` will be bound
   to the provided `conn` and the result is wrapped in a map containing :value or :error.

   (query (re-db.api/where ...)) => {:value _} or {:error _}"
  [conn & body]
  `(let [conn# ~conn]
     (r/reaction
      (db/with-conn conn#
        (try-value ~@body)))))

(defmacro defn-query
  "Defines a memoized query fn which tracks read-patterns (e-a-v) for reads via `re-db.api` and recomputes when
   matching datoms are transacted to a Datomic connection, which must be the first argument to the function.

   (defn-query $movie-by-title [conn title] (re-db.api/where ...))"
  [name & args]
  (let [args (cond-> args (string? (first args)) rest)]
    `(memo/def-memo ~name
       (let [f# (fn ~name ~@args)]
         (fn [conn# & args#]
           (re-db.query/reaction conn# (apply f# conn# args#)))))))

(defn register
  "Defines a reactive query function which tracks read-patterns (e-a-v) and recomputes when
   matching datoms are transacted to a Datomic connection (passed to the subscription/sub
   functions below)."
  [id f]
  (subs/register id
    (fn [conn args]
      (re-db.query/reaction conn (apply f args)))))

(defn patterns [q]
  (->> (r/get-derefs q)
       (keep (comp :pattern meta))))

(defn query
  "Looks up a registered query"
  ([qvec] (query (db/conn) qvec))
  ([conn qvec]
   (subs/subscription [(first qvec) conn (rest qvec)])))