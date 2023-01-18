(ns re-db.query
  (:require [re-db.api :as db]
            [re-db.reactive :as r]
            [re-db.subscriptions :as subs]
            [re-db.sync :as sync])
  #?(:cljs (:require-macros re-db.query)))

(defmacro bound-reaction
  ;; primarily for testing, when reactions shouldn't use the default db
  ;; (or for an app that uses more than one db)
  "Returns a reaction which evaluates `body`, in which calls using `re-db.api` will be bound
   to the provided `conn` and the result is wrapped in a map containing :value or :error.

   (query (re-db.api/where ...)) => {:value _} or {:error _}"
  [conn & body]
  `(let [conn# ~conn]
     (r/reaction
      (db/with-conn conn# (sync/try-value ~@body)))))

(defn register
  "Defines a reactive query function which tracks read-patterns (e-a-v) and recomputes when
   matching datoms are transacted to a Datomic connection (passed to the subscription/sub
   functions below)."
  [id f]
  (subs/register id
    (fn [conn args]
      (re-db.query/bound-reaction conn (apply f args)))))

(defn patterns [q]
  (->> (r/get-derefs q)
       (keep (comp :pattern meta))))

(defn query
  "Looks up a registered query"
  ([qvec] (query (db/conn) qvec))
  ([conn qvec]
   (subs/subscription [(first qvec) conn (rest qvec)])))