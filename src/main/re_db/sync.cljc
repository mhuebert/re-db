(ns re-db.sync
  "Support for watching refs across websocket(-like) connections"
  (:refer-clojure :exclude [send])
  (:require [re-db.api :as d]
            [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.memo :as memo]
            [re-db.util :as u]
            [re-db.xform :as xf])
  #?(:cljs (:require-macros re-db.sync)))

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

(defn wrap-value
  "Wraps a value result"
  [x]
  {:value x})

(memo/defn-memo $values
  "Stream of :sync/snapshot events associating `id` with values of `!ref`"
  [!ref]
  (xf/map wrap-value !ref))

(defn wrap-error
  "Wraps an error result"
  [x]
  {:error x})

(defn wrap-result
  "Returns a result message for a given query-id"
  [query-id the-result]
  [::result [query-id the-result]])

(defmacro try-value [& body]
  `(try {:value (do ~@body)}
        (catch ~(if (:ns &env) 'js/Error 'Exception) e#
          {:error (ex-message e#)})))

(defmacro reaction
  "A reaction which returns results compatible with re-db.sync a map containing (:value or :error)"
  [& body]
  `(r/reaction (try-value ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result handling (client)

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
  ([result-resolvers id message]
   (let [prev-result (read-result id)
         {:as result :keys [txs]} (resolve-result result-resolvers prev-result message)
         result (not-empty (dissoc result :txs))
         txs (cond-> []
                     result (conj [:db/add (db-id id) :result result])
                     txs (into txs))]
     (d/transact! txs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `Watch` bookkeeping (server) - which queries are being watched by which clients

;; map of {client-channel, #{...refs}}
(defonce !watches (atom {}))
;; for logging/inspection
(defonce !last-event (atom nil))

(defn watch
  "Adds watch for a local ref, sending messages to client via ::result messages.
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `Watch` bookkeeping (client) - which queries are being watched by this client

;; a map of {channel, {ref-id, #{...rx}}}
(defonce !watching (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket message utilities

(defn result-handlers
  ([] (result-handlers {}))
  ([resolve-result]
   {::result (fn [_ [id result]]
               (transact-result resolve-result id result))}))

(defn query-handlers [resolve-query]
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

(defn handle-message [handlers context message]
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

(memo/defn-memo $query
  "(client) Watch a value (by query, usually a vector).
   Returns map containing `:value`, `:error`, or `:loading?`}"
  [channel query]
  (send channel [::watch query])
  (let [rx (r/reaction
            (hooks/use-on-dispose
             (fn []
               (swap! !watching update channel dissoc query)
               (send channel [::unwatch query])))
            (read-result query))]
    (swap! !watching update channel assoc query rx)
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