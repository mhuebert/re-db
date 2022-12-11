(ns re-db.sync.server
  (:require [re-db.sync :as-alias sync]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client subscriptions - management of stateful websocket sessions.
;;
;; client-id can be any value uniquely associated with a client session (eg. session id or channel)

;; keep track of all subscriptions {client, #{...refs}}
(defonce !watches (atom {}))

(defn watch-ref
  "Adds watch for ref, syncing changes with client via :re-db.sync/tx messages.
   Values must be re-db transactions.
   The initial message checks for presence of :re-db.sync/initial-tx on the value
   (this is to facilitate stateful refs which, once initialized, only send diffs)"
  [client-id !ref send-fn]
  (swap! !watches update client-id (fnil conj #{}) !ref)
  (add-watch !ref client-id (fn [_ _ _ txs] (send-fn client-id [::sync/tx txs])))
  (send-fn client-id [::sync/tx (let [txs @!ref]
                                  (or (::sync/initial-tx (meta txs))
                                      txs))]))

(defn unwatch-ref
  "Removes watch for ref."
  [client-id !ref]
  (swap! !watches update client-id disj !ref)
  (remove-watch !ref client-id))

(defn unwatch-all [client-id]
  (doseq [ref (@!watches client-id)]
    (remove-watch ref client-id))
  (swap! !watches dissoc client-id)
  nil)
