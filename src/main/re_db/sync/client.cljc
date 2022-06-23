(ns re-db.sync.client
  (:require [re-db.reactive :as r]
            [re-db.api :as d]
            [re-db.subscriptions :as subs]
            [re-db.util :as u]
            [cognitect.transit :as transit]))

;; all active queries
(defonce !watching (atom {}))

;; messages queued before 1st connection
(defonce !initial-message-queue (atom []))

;; websocket-handling code needs to set the send-fn here
(defonce !send-fn (atom (fn [& args]
                          (js/console.warn (str ::!send-fn "Queueing message - send-fn not yet initialized"))
                          (swap! !initial-message-queue conj args))))
(defn set-send-fn! [f]
  (reset! !send-fn f
          #_(fn [& args]
              (prn :calling-send-fn args)
              (apply f args)))
  (doseq [args @!initial-message-queue] (apply f args))
  (swap! !initial-message-queue empty))

;; for instantiating entities
(def read-handlers {"re-db/entity" (transit/read-handler
                                    (fn [e]
                                      (let [[a v] e]
                                        (d/entity (if (= a :db/id)
                                                    v
                                                    [a v])))))})

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

(defn on-handshake! [send-fn]
  (set-send-fn! send-fn)
  (doseq [[qvec query] @!watching]
    (send-fn [:re-db.sync/watch-query qvec])))

(defn all [& qvecs]
  (let [qs (map (comp deref $query) qvecs)]
    (or (u/find-first qs :error)
        (u/find-first qs :loading?)
        {:value (into [] (map :value) qs)})))

(subs/def $all all)

;; MAYBE TODO...
;; - pass tx-id (to the client) with each query result
;; - when reconnecting, client passes this tx-id with the query
;; - server can re-run the query using db/as-of tx-id, compare with query "now", send only that diff