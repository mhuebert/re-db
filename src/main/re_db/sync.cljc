(ns re-db.sync
  "Support for watching refs across websocket(-like) connections"
  (:refer-clojure :exclude [send deliver])
  (:require [re-db.api :as d]
            [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.memo :as memo]
            [re-db.util :as u]))

;; Ideas
;; - everything is based on watching refs that contain streams of messages.
;; - use transducers to transform "simple values" into messages.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Channels - a channel represents either side of a client-server connection

(defn uid-channel
  "Returns a channel given a primitive uid and send-fn (a function of one argument, the message to be sent)"
  [uid send-fn]
  (with-meta [uid] {::send send-fn}))

(defn send
  "Sends a message to a channel (which must have a ::send key on itself or metadata)"
  [channel message]
  (let [send-fn (or (::send channel)
                    (::send (meta channel)))]
    (assert send-fn (str "send-fn not present on channel" channel))
    (send-fn message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query resolution (server)

(memo/defn-memo $result-map
  "Stream of :sync/snapshot events associating `id` with values of `!ref`"
  [!ref]
  (r/reaction
    (try {:value @!ref}
         (catch #?(:clj Exception :cljs js/Error) e
           {:error e}))))

(memo/defn-memo $catch [!ref f]
  (r/catch !ref f))

(defn wrap-result
  "Returns a result message for a given query-id"
  [query-id the-result]
  [::result [query-id the-result]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result handling (client)

(defn db-id
  "re-db id associated with the current stream"
  [descriptor]
  {::id descriptor})

(defn loading-promise
  "Custom promise implementation for suspense - completes via `deliver` without handling values"
  []
  #?(:clj (promise)
     :cljs
     (let [!resolved (volatile! false)
           !callbacks (volatile! [])]
       (reify
         Object
         (then [_ f]
           (if @!resolved
             (f nil)
             (vswap! !callbacks conj f)))
         (deliver [_]
           (vreset! !resolved true)
           (doseq [f @!callbacks] (f nil))
           (vswap! !callbacks empty))))))


(defn deliver [x]
  #?(:cljs (.deliver ^js x)
     :clj  (clojure.core/deliver x nil)))

(defn read-result [qvec]
  (d/get (db-id qvec) :result))

(defn- merge-result [result1 result2]
  (let [txs (:txs result2)
        m (not-empty (dissoc result2 :txs))]
    (cond-> result1
            txs (update :txs (fnil into []) txs)
            m (merge m))))

(defn resolve-result [result-resolvers prev result]
  (reduce-kv (fn [result k v]
               (if-let [f (get result-resolvers k)]
                 (merge-result result (f prev v))
                 (if (= k :txs)
                   (update result :txs (fnil into []) v)
                   (assoc result k v))))
             {}
             result))

(comment
 (resolve-result {:prefix (fn [{:keys [value]} prefix]
                            {:value (str prefix value)
                             :txs [[:db/add \a \b \c]]})}
                 {:value "Hello"}
                 {:prefix "PRE-"}))

(defn transact-result
  ([id message] (transact-result {} id message))
  ([result-resolvers qvec message]
   (let [prev-result (read-result qvec)
         {:as result :keys [txs]} (resolve-result result-resolvers prev-result message)
         result (not-empty (dissoc result :txs))
         txs (cond-> [[:db/add (db-id qvec) :result result]]
                     txs (into txs))
         tx-report (d/transact! txs)]
     (some-> (:loading? prev-result) deliver)
     tx-report)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `Watch` bookkeeping (server) - which queries are being watched by which clients

;; map of {client-channel, #{...refs}}
(defonce !watches (atom {}))
;; for logging/inspection
(defonce !last-event (atom nil))

(defn watch
  "(server) Adds watch for a local ref, sending messages to client via ::result messages.
   A ref's value may specify an `::init` result to send upon connection."
  [channel query !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id query})
  (alter-meta! !ref assoc ::query-id query) ;; mutate meta of !ref to include query-id for monitoring
  (swap! !watches update channel (fnil conj #{}) !ref)
  (add-watch !ref channel (fn [_ _ _ value]
                            (send channel (wrap-result query (dissoc value ::init)))))
  (let [v @!ref]
    (send channel (wrap-result query (or (::init v)
                                         (dissoc v ::init))))))

(defn unwatch
  "(server) Removes watch for ref."
  [channel !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id (::query-id (meta !ref))})
  (swap! !watches update channel disj !ref)
  (remove-watch !ref channel))

(defn unwatch-all ;; server
  "(server) Removes all watches for channel"
  [channel]
  (reset! !last-event {:event :unwatch-all :channel channel})
  (doseq [ref (@!watches channel)]
    (remove-watch ref channel))
  (swap! !watches dissoc channel)
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `Watch` bookkeeping (client) - which queries are being watched by this client

;; a map of {channel, {ref-id, #{...rx}}}
(defonce !watching (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket message utilities

(defn result-handlers
  "(client) Handlers for messages received by the client"
  ([] (result-handlers {}))
  ([resolve-result]
   {::result (fn [_ [id result]]
               (transact-result resolve-result id result))}))

(defn query-handlers
  "(server) Handlers for messages received by teh server"
  [resolve-query]
  {::watch
   (fn [{:keys [channel]} query]
     (if-let [!ref (resolve-query query)]
       (watch channel query !ref)
       (println "No ref found" query)))
   ::unwatch
   (fn [{:as context :keys [channel]} query]
     (if-let [!ref (resolve-query query)]
       (unwatch channel !ref)
       (println "No ref found" query)))})

(defn handle-message
  "Applies handler-fn (if found) to message args, with context as 1st param."
  [handlers context message]
  (if-let [handler (handlers (message 0))]
    (apply handler context (rest message))
    (println (str "Handler not found for: " message))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query utils (client)

(defn client:unwatch [channel query]
  (swap! !watching update channel dissoc query)
  (send channel [::unwatch query])
  (some-> (:loading? (read-result query)) deliver))

(memo/defn-memo $query
  "(client) Watch a value (by query, usually a vector).
   Returns map containing `:value`, `:error`, or `:loading?`}"
  [channel qvec]

  ;; set initial :loading? to a `suspended` value, to be resolved
  ;; when the first result arrives.
  #?(:cljs
     (when-not (read-result qvec)
       (d/transact! [[:db/add (db-id qvec) :result {:loading? (loading-promise)}]])))

  (send channel [::watch qvec])
  (r/reaction
    (hooks/use-effect
     (fn []
       (swap! !watching update channel assoc qvec r/*owner*)
       #(client:unwatch channel qvec)))
    (read-result qvec)))

(memo/defn-memo $all
  "Compose queries (by id). Returns first :loading? or :error,
   or joins results into a :value vector."
  [channel & qvecs]
  (r/reaction
    (let [qs (mapv (comp deref (partial $query channel)) qvecs)]
      (or (u/find-first qs :error)
          (u/find-first qs :loading?)
          {:value (into [] (map :value) qs)}))))