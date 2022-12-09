(ns re-db.sync.client
  (:require [re-db.reactive :as r]
            [re-db.api :as d]
            [re-db.hooks :as hooks]
            [re-db.subscriptions :as subs]
            [re-db.util :as u]))

;; all active queries
(defonce !watching (atom {}))

;; websocket-handling code needs to set the send-fn here
(defonce !send-fns (atom {}))

(defn send-message [socket message]
  (when-let [send! (@!send-fns socket)]
    (send! message)))

(defn transact-txs [txs]
  (d/transact! txs))

(subs/def $query
  (fn [socket qvec]
    (send-message socket [:re-db.sync/watch-query qvec])
    (let [rx (r/reaction
              (hooks/use-effect
               (constantly (fn dispose-query []
                             (swap! !watching dissoc qvec)
                             (send-message socket [:re-db.sync/unwatch-query qvec]))))
              (d/get {:re-db/query-result qvec} :result {:loading? true}))]
      (swap! !watching assoc qvec rx)
      rx)))

(defn on-open [channel send-fn]
  (swap! !send-fns assoc channel send-fn)
  (doseq [[qvec _] @!watching]
    (send-fn [:re-db.sync/watch-query qvec])))

(defn on-close [socket]
  (swap! !send-fns dissoc socket))

(defn all [socket & qvecs]
  (r/reaction
   (let [qs (mapv (comp deref (partial $query socket)) qvecs)]
     (or (u/find-first qs :error)
         (u/find-first qs :loading?)
         {:value (into [] (map :value) qs)}))))

(subs/def $all all)

;; MAYBE TODO...
;; - pass tx-id (to the client) with each query result
;; - when reconnecting, client passes this tx-id with the query
;; - server can re-run the query using db/as-of tx-id, compare with query "now", send only that diff