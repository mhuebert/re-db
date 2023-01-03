(ns re-db.query
  (:require [re-db.api]
            [re-db.memo]
            [re-db.reactive])
  #?(:cljs (:require-macros re-db.query)))

;; macros for deprecated names to throw at compile time
(defmacro ^:deprecated register [id f]
  (throw (ex-info (str `register " is deprecated; use `defn-query` instead") {:id id})))
(defmacro ^:deprecated query [& args]
  (throw (ex-info (str `query " is deprecated; use `defn-query` instead") {:args args})))

(defmacro reaction
  "Returns a reaction which evaluates `body`, in which calls using `re-db.api` will be bound
   to the provided `conn` and the result is wrapped in a map containing :value or :error.

   (query (re-db.api/where ...)) => {:value _} or {:error _}"
  [conn & body]
  `(let [conn# ~conn]
     (re-db.reactive/reaction
      (re-db.api/with-conn conn#
        (try {:value (do ~@body)}
             (catch ~(if (:ns &env) 'js/Error 'Exception)
                    e# {:error (ex-message e#)}))))))

(defmacro defn-query
  "Defines a memoized query fn which tracks read-patterns (e-a-v) for reads via `re-db.api` and recomputes when
   matching datoms are transacted to a Datomic connection, which must be the first argument to the function.

   (defn-query $movie-by-title [conn title] (re-db.api/where ...))"
  [name & args]
  (let [args (cond-> args (string? (first args)) rest)]
    `(re-db.memo/def-memo ~name
       (let [f# (fn ~name ~@args)]
         (fn [conn# & args#]
           (reaction conn# (apply f# conn# args#)))))))

