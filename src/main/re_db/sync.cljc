(ns re-db.sync
  "Support for watching refs across websocket(-like) connections"
  (:refer-clojure :exclude [send])
  (:require [re-db.api :as d]
            [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.memo :as memo]
            [re-db.util :as u]
            [re-db.xform :as xf]))

;; Ideas
;; - everything is based on watching refs that contain streams of messages.
;; - use transducers to transform "simple values" into messages.

;; Channels - long-lived connections to peers
;; A channel must be an atom containing a `:send` function

(defn send [channel message] ((:send @channel) message))

;; **Watches** - local refs being sent to connected peers

;; map of {client-channel, #{...refs}}
(defonce !watches (atom {}))

(defn watch
  "Adds watch for a local ref, sending messages to client via :re-db.sync/tx messages.
   A ref's value may specify `:sync/snapshot` metadata to override the initial message
   sent when starting a watch."
  [channel descriptor !ref]
  (swap! !watches update channel (fnil conj #{}) !ref)
  (add-watch !ref channel (fn [_ _ _ value] (send channel [:sync/watched-result descriptor
                                                           (dissoc value :sync/init)])))
  (let [v @!ref]
    (send channel [:sync/watched-result
                   descriptor
                   (or (:sync/init v)
                       (dissoc v :sync/init))])))

(defn unwatch
  "Removes watch for ref."
  [channel !ref]
  (swap! !watches update channel disj !ref)
  (remove-watch !ref channel))

(defn unwatch-all
  "Removes all watches for channel"
  [channel]
  (doseq [ref (@!watches channel)]
    (remove-watch ref channel))
  (swap! !watches dissoc channel)
  nil)

;; **Watching** - remote refs being sent to this instance

;; a map of {channel, {ref-id, #{...rx}}}
(defonce !watching (atom {}))

(defn db-id
  "re-db id associated with the current stream"
  [descriptor]
  {:sync/descriptor descriptor})

(defn read-result [qvec]
  (d/get (db-id qvec) :result {:loading? true}))

(defn resolve-message [resolvers prev id m]
  (reduce-kv (fn [out k v]
               (if-let [f (resolvers k)]
                 (let [result (f prev v)]
                   (-> (merge out result)
                       (dissoc k)
                       (assoc :txs ((fnil into [])
                                    (:txs out)
                                    (:txs result)))))
                 out))
             m
             (dissoc m :value :error :txs)))

(defn handle-message [resolvers id message]
  (let [{:as result :keys [txs]} (resolve-message resolvers (read-result id) id message)
        result (not-empty (dissoc result :txs))
        txs (cond-> []
                    result (conj [:db/add (db-id id) :result result])
                    txs (into txs))]
    (d/transact! txs)))

(memo/defn-memo $values
  "Stream of :sync/snapshot events associating `id` with values of `!ref`"
  [!ref]
  (xf/map (fn [value] {:value value}) !ref))


;; Channel lifecycle functions (to be called at appropriate stages of a long-lived server connection)

(defn on-open
  "Call when a (server) channel opens to initiate watches"
  [channel]
  (doseq [[qvec _] (@!watching channel)]
    (send channel [:sync/watch qvec])))

(defn on-close
  "Call when a (client) channel closes top stop watches"
  [channel]
  (unwatch-all channel))

;; Client subscriptions

(memo/defn-memo $watch
  "Watch a server-side value (by id). Returns map containing one of {:value, :error, :loading?}"
  [channel qvec]
  (send channel [:sync/watch qvec])
  (let [rx (r/reaction
            (hooks/use-on-dispose
             (fn []
               (swap! !watching update channel dissoc qvec)
               (send channel [:sync/unwatch qvec])))
            (read-result qvec))]
    (swap! !watching update channel assoc qvec rx)
    rx))

(comment
 ;; convenience subscription -  should go somewhere else
 (memo/defn-memo $all
   "Compose any number of watches (by id). Returns first :loading? or :error,
    or joins results into a :value vector."
   [channel & qvecs]
   (r/reaction
    (let [qs (mapv (comp deref (partial $watch channel)) qvecs)]
      (or (u/find-first qs :error)
          (u/find-first qs :loading?)
          {:value (into [] (map :value) qs)})))))

