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

(defn send [channel message]
  (let [send-fn (or (::send channel)
                    (::send (meta channel)))]
    (assert send-fn (str "send-fn not present on channel" channel))
    (send-fn message)))

(defn uid-channel
  "Returns a channel (supporting `send`) given a primitive uid and send-fn"
  [uid send-fn]
  (with-meta [uid] {::send send-fn}))

(defn value [x] {:value x})
(defn error [x] {:error x})
(defn result [id x] [::result [id x]])

;; **Watches** - local refs being sent to connected peers

;; map of {client-channel, #{...refs}}
(defonce !watches (atom {}))
(defonce !last-event  (atom nil))

(defn watch
  "Adds watch for a local ref, sending messages to client via :re-db.sync/tx messages.
   A ref's value may specify `:sync/snapshot` metadata to override the initial message
   sent when starting a watch."
  [channel query-vec !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id query-vec})
  (r/update-meta! !ref assoc ::query-id query-vec) ;; mutate meta of !ref to include query-id for monitoring
  (swap! !watches update channel (fnil conj #{}) !ref)
  (add-watch !ref channel (fn [_ _ _ value]
                            (send channel (result query-vec (dissoc value ::init)))))
  (let [v @!ref]
    (send channel (result query-vec (or (::init v)
                                       (dissoc v ::init))))))

(defn unwatch
  "Removes watch for ref."
  [channel !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id (::query-id (meta !ref))})
  (swap! !watches update channel disj !ref)
  (remove-watch !ref channel))

(defn unwatch-all
  "Removes all watches for channel"
  [channel]
  (reset! !last-event {:event :unwatch-all :channel channel})
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
  {::id descriptor})

(defn read-result [qvec]
  (d/get (db-id qvec) :result {:loading? true}))

(defn- merge-result [result1 result2]
  (let [txs (:txs result2)
        m (not-empty (dissoc result2 :txs))]
    (cond-> result1
            txs (update :txs (fnil into []) txs)
            m (merge m))))

(defn resolve-message [result-handlers prev result]
  (reduce-kv (fn [result k v]
               (if-let [f (get result-handlers k)]
                 (merge-result result (f prev v))
                 (if (= k :txs)
                   (update result :txs (fnil into []) v)
                   (assoc result k v))))
             {}
             result))

(comment
 (resolve-message {:prefix (fn [{:keys [value]} prefix]
                             {:value (str prefix value)
                              :txs [[:db/add \a \b \c]]})}
                  {:value "Hello"}
                  {:prefix "PRE-"}))

(defn transact-result
  ([id message] (transact-result {} id message))
  ([result-handlers id message]
   (let [prev-result (read-result id)
         {:as result :keys [txs]} (resolve-message result-handlers prev-result message)
         result (not-empty (dissoc result :txs))
         txs (cond-> []
                     result (conj [:db/add (db-id id) :result result])
                     txs (into txs))]
     (d/transact! txs))))

(memo/defn-memo $values
  "Stream of :sync/snapshot events associating `id` with values of `!ref`"
  [!ref]
  (xf/map value !ref))


;; Channel lifecycle functions (to be called at appropriate stages of a long-lived server connection)

(defn on-open
  "(client) Call when connected to server, to initiate active watches."
  [channel]
  (doseq [[qvec _] (@!watching channel)]
    (send channel [::watch qvec])))

(defn on-close
  "(client) Call when a connection closes, to stop all watches."
  [channel]
  (unwatch-all channel))

;; Client subscriptions

(memo/defn-memo $query
  "(client) Watch a value value (by query-id, usually a vector).
   Returns map containing `:value`, `:error`, or `:loading?`}"
  [channel query-id]
  (send channel [::watch query-id])
  (let [rx (r/reaction
            (hooks/use-on-dispose
             (fn []
               (swap! !watching update channel dissoc query-id)
               (send channel [::unwatch query-id])))
            (read-result query-id))]
    (swap! !watching update channel assoc query-id rx)
    rx))

(memo/defn-memo $all
  "Compose queries (by id). Returns first :loading? or :error,
   or joins results into a :value vector."
  [channel & qvecs]
  (r/reaction
   (let [qs (mapv (comp deref (partial $query channel)) qvecs)]
     (or (u/find-first qs :error)
         (u/find-first qs :loading?)
         {:value (into [] (map :value) qs)}))))

