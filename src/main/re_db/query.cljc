(ns re-db.query
  (:require [re-db.read :refer [*conn*]]
            [re-db.reactive :as r]
            [re-db.subscriptions :as subs]))

;; a lightweight wrapper around r/reaction - unsure if this API should exist

(defn compute-f [id conn f]
  (binding [*conn* conn]
    (try {:value (f)}
         (catch #?(:clj Exception :cljs js/Error) e
           (prn :error-computing-query id (ex-message e))
           {:error (ex-message e)}))))

(defn register
  "Defines a reactive query function which tracks read-patterns (e-a-v) and recomputes when
   matching datoms are transacted to a Datomic connection (passed to the subscription/sub
   functions below)."
  [id f]
  (subs/register id
    (fn [conn args]
      (r/reaction (compute-f id conn #(apply f args))))))

(defn patterns [q] (->> (r/get-derefs q)
                        (keep (comp :pattern meta))))

(defn query
  ([qvec] (query *conn* qvec))
  ([conn qvec]
   (subs/subscription [(first qvec) conn (rest qvec)])))