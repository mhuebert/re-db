(ns re-db.sync.client
  (:require [re-db.reactive :as r]
            [re-db.api :as d]
            [re-db.subscriptions :as subs]
            [cognitect.transit :as transit]))

;; keeps track of all the queries we are watching
(defonce !watching (atom {}))

(defonce !message-queue (atom []))

;; websocket-handling code needs to set the send-fn here
(defonce !send-fn (atom (fn [& args]
                          (js/console.warn (str ::!send-fn "Queueing message - send-fn not yet initialized"))
                          (swap! !message-queue conj args))))
(defn set-send-fn! [f]
  (reset! !send-fn (fn [& args] (apply f args)))
  (doseq [args @!message-queue] (apply f args))
  (swap! !message-queue empty))

;; for instantiating entities
(def read-handlers {"re-db/entity" (transit/read-handler
                                    (fn [e]
                                      (d/entity (if (array? e)
                                                  (.fromArray PersistentVector e true)
                                                  e))))})

(defn transact-txs [txs]
  (d/transact! txs))

(subs/def $query
  (fn [qvec]
    (@!send-fn [:re-db.sync/watch-query qvec])
    (let [rx (r/make-reaction #(d/get {:re-db/query-result qvec} :result {:loading? true})
                              :on-dispose (fn [_]
                                            (swap! !watching dissoc qvec)
                                            (@!send-fn [:re-db.sync/unwatch-query qvec])))]
      (swap! !watching assoc qvec rx)
      rx)))

(defn $eval [form] ($query [:eval form]))

(defn on-handshake! [send-fn]
  (set-send-fn! send-fn)
  (doseq [[qvec query] @!watching]
    (send-fn [:re-db.sync/watch-query qvec])))


;; MAYBE TODO...
;; - pass tx-id (to the client) with each query result
;; - when reconnecting, client passes this tx-id with the query
;; - server can re-run the query using db/as-of tx-id, compare with query "now", send only that diff