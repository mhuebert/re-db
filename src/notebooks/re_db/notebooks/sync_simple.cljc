^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync-simple
  (:require [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [show-cljs cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [re-db.notebooks.tools.websocket :as websocket]
            [re-db.reactive :as r]
            [re-db.sync :as sync]
            [re-db.sync.client :as client]
            [re-db.sync.server :as server]
            [re-db.sync.transit :as t]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; # Sync

;; This namespace demonstrates how to sync data from a server to client using
;; `re-db.sync.server` and `re-db.sync.client`.

;; We can sync any "watchable" thing, so let's use a plain atom.

(defonce !counter (atom 0))

;; Let's make a map of "syncable things" (refs) that we want to expose to clients.
;; Each ref will have a name that clients can use to request it, eg `:counter`:

(defmulti resolve-ref (fn [qvec] (first qvec)))

(defmethod resolve-ref :counter
  [_]
  (sync/$values !counter))

; For handling websocket messages we use the `handle-message` multimethod.

(defmulti handle-message (fn [channel [op]] op))

;; We'll need two operations for the server, `::sync/watch` and `::sync/unwatch`.
;; Their job is to resolve the id from the client to a ref (any watchable thing),
;; then pass that to `server/watch` and `server/unwatch` respectively.

(defmethod handle-message ::sync/watch
  [channel [_ qvec]]
  (when-let [ref (resolve-ref qvec)]
    (server/watch ref channel (fn [channel [op & args]]
                                (websocket/send! channel (into [op qvec] args))))))

(defmethod handle-message ::sync/unwatch
  [channel [_ qvec]]
  (when-let [ref (resolve-ref qvec)]
    (server/unwatch ref channel)))

;; Start a websocket server with a `handle-message` and `on-close` function:

#?(:clj
   (defonce server
     (websocket/serve
      {:port 9060
       :path "/ws"
       :pack t/pack
       :unpack t/unpack
       :on-message (fn [!channel message]
                     (handle-message !channel message))
       :on-close (fn [channel status]
                   (server/unwatch-all channel))})))

;; The client needs to handle messages from the server, which will come in the form
;; `[operation ref-id & args]`

;; For the client we'll implement `::sync/value` for writing a value associated
;; with a watched ref to the local cache.

(defmethod handle-message ::sync/value
  [channel [op ref-id value]]
  (db/transact! (client/set-result-tx ref-id {:value value})))


(cljs
 (defonce ^js channel
   (websocket/connect {:url "ws://localhost:9060/ws"
                       :pack t/pack
                       :unpack t/unpack
                       :on-open (fn [channel]
                                  (client/on-open channel (partial websocket/send! channel)))
                       :on-message handle-message
                       :on-close client/on-close})))

;; Show our result:

(show-cljs
 (let [result @(client/$watch channel [:counter])]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                [:span.font-bold (:value result)]])))

;; Increment the counter from the browser:
(show-cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !counter inc))} "Number, go up!"])


(cljs
 (def !log
   (xf/transform (:!last-message @channel)
     (take 10)
     (xf/into ()))))

;; Collect and show messages seen by the server:
(show-cljs
 [:div.whitespace-pre-wrap.code.text-xs
  (with-out-str (pprint @!log))])

;; TODOs

;; - entity sync: sending re-db entity references across the wire
;; - entity diff: pushing patches instead of entire queries
;;   - opportunities for improvement and optimization:
;;     - subqueries
;;     - better diffs/patches, different convergence methods (eg. CRDT, OT)
;; - reactive db queries (which invalidate based on a tx-log)
;; - paginating queries
;; - subscriptions: what they are, how they work
;; - xforms: ratoms and reactions as streams with transducers

^::clerk/visibility {:code :hide :result :hide}
(do
  (defmethod resolve-ref :default [qvec]
    (println :no-ref! qvec))
  (defmethod handle-message :default [& args]
    (prn :no-message-handler! args)))