^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync
  (:require [applied-science.js-interop :as j]
            [mhuebert.clerk-cljs :refer [cljs]]
            [re-db.query :as q]
            [re-db.sync.transit :as t]
            [re-db.sync.server :as sync.server]
            [re-db.reactive :as r]
            [re-db.integrations.reagent]
            [re-db.notebooks.websocket :as ws]
            [re-db.sync :as-alias sync]
            [re-db.sync.client :as sync.client]
            #?@(:clj
                [[re-db.notebooks.datomic :refer [conn]]
                 [clojure.pprint :refer [pprint]]])))

(def PORT 9060)

;; Start by defining a simple reactive source on the server, a counter:

#?(:clj
   (defonce !counter (r/atom 0)))

;; Define a query which dereferences the counter:
#?(:clj
   (q/register :counter #(deref !counter)))


;; ### Open a websocket server

#?(:clj
   (defn on-message [channel message]
     (let [[event qvec] (t/unpack message)]
       (case event
         ::sync/watch-query
         (sync.server/watch-ref channel
                                qvec
                                (q/query conn qvec)
                                (fn [channel message]
                                  (ws/send! channel (t/pack message))))
         ::sync/unwatch-query
         (sync.server/unwatch-ref channel
                                  qvec
                                  (q/query conn qvec))))))

#?(:clj
   (ws/serve {:port PORT
              :on-close (fn [channel status]
                          (prn :unwatch-all channel)
                          (sync.server/unwatch-all channel))
              :on-message #'on-message}))

;; ## Connect to the websocket server from the browser

(cljs
 (def socket
   (ws/connect {:port PORT
                :on-open (fn [socket]
                           (sync.client/on-open socket
                             (fn [message]
                               (ws/send! socket (t/pack message)))))
                :on-message (fn [socket message]
                              (let [[event data] (t/unpack message)]
                                (case event
                                  ::sync/tx (sync.client/transact-txs data))))
                :on-close sync.client/on-close})))

(cljs
 (let [{:keys [value
               error
               loading?]} @(sync.client/$query socket [:counter])]
   (cond loading? "loading..."
         error [:div "Error: " error]
         :else [:div "Count: " value])))

(comment
 (swap! !counter inc))

;; TODO

;; - diffing
;; - entities
;; - create transactions from the client
