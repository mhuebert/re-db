^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync-simple
  (:require [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [re-db.notebooks.tools.websocket :as websocket]
            [re-db.subscriptions :as subs]
            [re-db.sync :as sync]
            [re-db.sync.registry :refer [register handle]]
            [re-db.sync.client :as client]
            [re-db.sync.server :as server]
            [re-db.sync.transit :as t]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; # Sync

;; This namespace demonstrates how to sync data from a server to client using
;; `re-db.sync.server` and `re-db.sync.client`. We'll touch on a few concepts:

;; 1. Using a "message vector" (`mvec`) format for client-server communication
;; 2. Clojure's `add-watch` as a basis for data streams
;; 3. Using transducers to reshape data streams

;; ### Message vectors and resolvers

;; For re-db instances to tell each other what data they're looking for, they'll send
;; messages in the following shape: `[:name-of-query & args]` - a vector beginning with an
;; identifier, followed by arguments. We call this a "message vector" or `mvec` in code.
;; Code for registering and invoking handlers can be found in `re-db.sync.registry`.

;; In Clojure, the `IWatchable` / `clojure.lang.IRef` protocol is most often encountered with
;; atoms, but can be implemented for any type. It lets us reliably subscribe to changes
;; which occur on a reference type by registering a callback which will be called with
;; before/after values for every change.

;; Let's create an atom that we'll allow browser clients to watch.

(defonce !counter (atom 0))

;; To serve our `!counter` atom, we'll implement a resolver for `:counter`.

(register :counter
  (fn [context]
    (sync/$snapshots (:mvec context) !counter)))

;; Note that we wrapped our atom in `sync/$snapshots`. This is a **subscription** which transforms 
;; `!counter`, wrapping each successive value in a `[::sync/snapshot ...]` message.

;; Our handler for `::sync/snapshot` resets a query result by transacting to the local re-db:

(register ::sync/snapshot
  (fn [context qvec value]
    (db/transact! (client/set-result-tx qvec {:value value}))))

;; `::sync/watch` and `::sync/unwatch` delegate to `re-db.sync.server` after resolving their ref
;; (also a message vector, must resolve to some watchable thing)

(register
  ::sync/watch (fn [context qvec]
                 (if-let [!ref (handle context qvec)]
                   (server/watch !ref (:channel context) websocket/send!)
                   (println "No ref found" qvec)))
  ::sync/unwatch (fn [context qvec]
                   (if-let [!ref (handle context qvec)]
                     (server/unwatch !ref (:channel context))
                     (println "No ref found" qvec))))

;; Let's start a websocket server, passing in our `handle-message` function for messages and
;; `server/unwatch-all` for close events. This is clj-only. (We're using a tiny websocket server
;; implemented on top of http-kit in `re-db.notebooks.tools.websocket`)

#?(:clj
   (defonce server
     (websocket/serve
      {:port 9060
       :path "/ws"
       :pack t/pack
       :unpack t/unpack
       :on-message (fn [channel mvec]
                     (handle {:channel channel} mvec))
       :on-close (fn [channel status]
                   (server/unwatch-all channel))})))

;; And here's our client, running in the browser (cljs):

#?(:cljs
   (defonce ^js channel
            (websocket/connect {:url "ws://localhost:9060/ws"
                                :pack t/pack
                                :unpack t/unpack
                                :on-open (fn [!channel]
                                           ;; TODO
                                           ;; get rid of this on-open send-fn stuff,
                                           ;; websocket should handle queued messages
                                           ;; internally.
                                           (client/on-open !channel (:send @!channel)))
                                :on-message (fn [channel message] (handle {:channel channel} message))
                                :on-close client/on-close})))

;; To watch a query, we use `re-db.client/$watch` subscription, passing it the
;; mvec `[:counter]`. It returns a map containing one of `:loading?`, `:error` or `:value`.

(show-cljs
 (let [result @(client/$watch channel [:counter])]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                [:span.font-bold (:value result)]])))

;; Try incrementing the counter (on click, it uses `clerk-eval` to run code in the JVM environment)
(show-cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !counter inc))} "Number, go up!"])

;; Defining a log of messages seen by the channel:
#?(:cljs
   (def !log
     (xf/transform (:!last-message @channel)
       (keep identity)
       (xf/sliding-window 10))))

;; Show the log using `pprint`:

(show-cljs
 [:div.whitespace-pre-wrap.code.text-xs
  (with-out-str (pprint @!log))])
