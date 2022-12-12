(ns re-db.sync.client
  (:require [re-db.api :as d]
            [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.subscriptions :as subs]
            [re-db.sync :as-alias sync]
            [re-db.util :as u]))

;; Re-DB's sync client.

;; all active queries, {channel {id #{...rx}}}
(defonce !watching (atom {}))

;; send-fns for channels
(defonce !send-fns (atom {}))

(defn send-message
  "Send a message to a channel"
  [channel message]
  (when-let [send! (@!send-fns channel)]
    (send! message)))

;; re-db transaction format for synced results

(defn db-id
  "The local re-db id under which results will be stored"
  [id]
  {:re-db.sync id})

(defn read-result [id]
  (d/get (db-id id) :result {:loading? true}))

(defn set-result-tx [id result]
  [[:db/add (db-id id) :result result]])

;; Channel lifecycle functions (to be called at appropriate stages of a long-lived server connection)

(defn on-open
  "Must be called when a long-lived server connection opens.
  `channel` should be a unique identifier for this connection.
   `send-fn` should be a function of [message] which sends a message to this connection."
  [channel send-fn]
  (swap! !send-fns assoc channel send-fn)
  (doseq [[id _] (@!watching channel)]
    (send-fn [::sync/watch id])))

(defn on-close [channel]
  (swap! !send-fns dissoc channel))

;; Client subscriptions

(subs/def $watch
  "Watch a server-side value (by id). Returns map containing one of {:value, :error, :loading?}"
  ;; the server is responsible for mapping `id` to a data source
  (fn [channel id]
    (send-message channel [::sync/watch id])
    (let [rx (r/reaction
              (hooks/use-on-dispose
               (fn []
                 (swap! !watching update channel dissoc id)
                 (send-message channel [::sync/unwatch id])))
              (read-result id))]
      (swap! !watching update channel assoc id rx)
      rx)))

(subs/def $all
  "Compose any number of watches (by id). Returns first :loading? or :error,
   or joins results into a :value vector."
  (fn [channel & ids]
    (r/reaction
     (let [qs (mapv (comp deref (partial $watch channel)) ids)]
       (or (u/find-first qs :error)
           (u/find-first qs :loading?)
           {:value (into [] (map :value) qs)})))))

