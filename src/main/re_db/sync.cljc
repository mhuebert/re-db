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
  (add-watch !ref channel (fn [_ _ _ value] (send channel [:sync.client/handle-result descriptor
                                                           (dissoc value :sync/init)])))
  (let [v @!ref]
    (send channel [:sync.client/handle-result
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

(defn transact-result [result-handlers id message]

  (let [prev-result (read-result id)
        {:as result :keys [txs]} (resolve-message result-handlers prev-result message)
        result (not-empty (dissoc result :txs))
        txs (cond-> []
                    result (conj [:db/add (db-id id) :result result])
                    txs (into txs))]
    (d/transact! txs)))

(memo/defn-memo $values
  "Stream of :sync/snapshot events associating `id` with values of `!ref`"
  [!ref]
  (xf/transform !ref
    (map (fn [value] {:value value}))))


;; Channel lifecycle functions (to be called at appropriate stages of a long-lived server connection)

(defn on-open
  "Call when a (server) channel opens to initiate watches"
  [channel]
  (doseq [[qvec _] (@!watching channel)]
    (send channel [:sync.server/watch qvec])))

(defn on-close
  "Call when a (client) channel closes top stop watches"
  [channel]
  (unwatch-all channel))

;; Client subscriptions

(memo/defn-memo $watch
  "Watch a server-side value (by id). Returns map containing one of {:value, :error, :loading?}"
  [channel qvec]
  (send channel [:sync.server/watch qvec])
  (let [rx (r/reaction
            (hooks/use-on-dispose
             (fn []
               (swap! !watching update channel dissoc qvec)
               (send channel [:sync.server/unwatch qvec])))
            (read-result qvec))]
    (swap! !watching update channel assoc qvec rx)
    rx))

(defn watch-handlers [& {:keys [result-handlers
                                resolve-ref]
                         :or {result-handlers {}
                              resolve-ref {}}}]
  {:sync.client/handle-result
   (fn [_ id target]
     (transact-result result-handlers id target))
   :sync.server/watch
   (fn [{:keys [channel]} ref-id]
     (if-let [!ref (resolve-ref ref-id)]
       (watch channel ref-id !ref)
       (println "No ref found" ref-id)))
   :sync.server/unwatch
   (fn [{:as context :keys [channel]} ref-id]
     (if-let [!ref (resolve-ref ref-id)]
       (unwatch channel !ref)
       (println "No ref found" ref-id)))})

(defn handle-message [handlers context mvec]
  (if-let [handler (handlers (mvec 0))]
    (apply handler context (rest mvec))
    (println (str "Handler not found for: " mvec))))

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

