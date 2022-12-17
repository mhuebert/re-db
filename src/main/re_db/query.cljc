(ns re-db.query
  (:require [re-db.api]
            [re-db.memo]
            [re-db.reactive])
  #?(:cljs (:require-macros re-db.query)))

(defn register

  [id f]
  (throw (ex-info (str `register " is deprecated; use `defn-query` instead") {:id id})))

(defn query [& args] (throw (ex-info (str `query " is deprecated; use `defn-query` instead") {:args args})))

(defmacro defn-query
  "Defines a memoized query fn which tracks read-patterns (e-a-v) for reads via `re-db.api` and recomputes when
   matching datoms are transacted to a Datomic connection, which must be the first argument to the function.

   (defn-query $movie-by-title [conn title] (re-db.api/where ...))"
  [name & args]
  `(re-db.memo/def-memo ~name
     (let [f# (fn ~@args)]
       (fn [conn# & args#]
         (re-db.reactive/reaction
          (re-db.api/with-conn conn#
            (try {:value (apply f# conn# args#)}
                 (catch ~(if (:ns &env) 'js/Error 'Exception)
                        e# {:error (ex-message e#)}))))))))