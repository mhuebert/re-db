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
    (try {:value (hooks/use-deref !ref)}
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

(defn loading-promise
  "Custom promise implementation for suspense - completes via `deliver` without handling values"
  []
  #?(:clj (promise)
     :cljs
     (let [!resolved (volatile! false)
           !value (volatile! nil)
           !callbacks (volatile! [])]
       (reify
         Object
         (then [_ f]
           (if @!resolved
             (f @!value)
             (vswap! !callbacks conj f)))
         (deliver [_ value]
           (vreset! !resolved true)
           (vreset! !value value)
           (doseq [f @!callbacks] (f value))
           (vswap! !callbacks empty))))))


(defn deliver [x value]
  #?(:cljs (.deliver ^js x value)
     :clj  (clojure.core/deliver x value)))

(memo/defn-memo $result-ratom
  "Ratom for storing the result of a query."
  [qvec]
  (r/atom {:loading? (loading-promise)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `Watch` bookkeeping (client) - which queries are being watched by this client

;; a map of {channel, {ref-id, #{...rx}}}
(defonce !watching (atom {}))

(defn client:unwatch [channel qvec]
  (swap! !watching update channel dissoc qvec)
  (send channel [::unwatch qvec])
  (some-> (:loading? @($result-ratom qvec)) (deliver nil)))

(memo/defn-memo $query
  "(client) Watch a value (by query, usually a vector)."
  [channel qvec]
  (r/reaction
    {:meta {:dispose-delay (:dispose-delay channel)}}
    (hooks/use-effect
     (fn []
       (send channel [::watch qvec])
       (swap! !watching update channel assoc qvec r/*owner*)
       #(client:unwatch channel qvec)))
    @($result-ratom qvec)))


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
             (dissoc prev :txs :loading?)
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
   (let [!result ($result-ratom qvec)
         prev-result @!result
         {:as result :keys [txs]} (resolve-result result-resolvers prev-result message)
         tx-report (some-> txs d/transact!)
         result (not-empty (dissoc result :txs))]
     (reset! !result result)
     ;; TODO
     ;; put these two effects into a single "transaction"?
     ;; (wait to notify until all complete)
     (some-> (:loading? prev-result) (deliver @!result))
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
  [channel qvec !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id qvec})
  (alter-meta! !ref assoc ::query-id qvec)                 ;; mutate meta of !ref to include query-id for monitoring
  (swap! !watches update channel (fnil conj #{}) !ref)
  (add-watch !ref channel (fn [_ _ _ value]
                            (send channel (wrap-result qvec (dissoc value ::init)))))
  (let [v @!ref]
    (send channel (wrap-result qvec (or (::init v)
                                         (dissoc v ::init))))))

(defn unwatch
  "(server) Removes watch for ref."
  [channel !ref]
  (reset! !last-event {:event :watch-ref :channel channel :query-id (::query-id (meta !ref))})
  (swap! !watches update channel disj !ref)
  (remove-watch !ref channel))

(defn unwatch-all                                           ;; server
  "(server) Removes all watches for channel"
  [channel]
  (reset! !last-event {:event :unwatch-all :channel channel})
  (doseq [ref (@!watches channel)]
    (remove-watch ref channel))
  (swap! !watches dissoc channel)
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket message utilities

(defn result-handlers
  "(client) Handlers for messages received by the client"
  ([] (result-handlers {}))
  ([handlers]
   {::result (fn [_ [id result]]
               (transact-result handlers id result))}))

(defn query-handlers
  "(server) Handlers for messages received by the server"
  [resolve-query]
  {::watch
   (fn [{:as context :keys [channel]} qvec]
     (if-let [!ref (resolve-query (with-meta qvec (assoc context ::watch true)))]
       (watch channel qvec !ref)
       (println "No ref found" qvec)))
   ::unwatch
   (fn [{:as context :keys [channel]} qvec]
     (if-let [!ref (resolve-query (with-meta qvec (assoc context ::unwatch true)))]
       (unwatch channel !ref)
       (println "No ref found" qvec)))})

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

(memo/defn-memo $all
  "Compose queries (by id). Returns first :loading? or :error,
   or joins results into a :value vector."
  [channel & qvecs]
  (r/reaction
    (let [qs (mapv (comp deref (partial $query channel)) qvecs)]
      (or (u/find-first qs :error)
          (u/find-first qs :loading?)
          {:value (into [] (map :value) qs)}))))