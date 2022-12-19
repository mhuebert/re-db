^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync-values
  (:require [clojure.core.match :refer [match]]
            [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.integrations.reagent]
            [re-db.notebooks.tools.websocket :as ws]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            [re-db.hooks :as hooks]
            [re-db.memo :as memo]
            #?(:cljs [nextjournal.clerk.render :as render])
            [re-db.reactive :as r]))

;; resolve-ref: from a descriptor (+ context) to a stream

;; # Sync

;; This namespace demonstrates how to sync data from a server to client using
;; `re-db.sync.server` and `re-db.sync.client`. We'll use _subscriptions_ to let multiple
;; clients re-use the same data streams, and _transducers_ to flexibly transform these
;; streams.

;; For example purposes, we've implemented a basic websocket connection for passing
;; _messages_ between a client and server (see `re-db.notebooks.tools.websocket`).

;; ## Watch and Unwatch


(defonce !counter (atom 0))

;; Let's start a websocket server, passing in our `handle-message` function for messages and
;; `sync/unwatch-all` for close events. This is clj-only. (We're using a tiny websocket server
;; implemented on top of http-kit in `re-db.notebooks.tools.websocket`)

#?(:clj
   (defonce server
     (ws/serve {:port 9060
                :path "/ws"
                :resolve-ref (fn [context descriptor]
                               (match descriptor :counter (sync/$values !counter)))})))

(show-cljs
 (defonce channel (ws/connect {:port 9060
                               :path "/ws"})))

;; To watch a query, we use `re-db.client/$watch` subscription, passing it the
;; mvec `[:counter]`. It returns a map containing one of `:loading?`, `:error` or `:value`.

(show-cljs

 (let [result @(sync/$watch channel :counter)]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                [:span.font-bold (:value result)]])))

;; Try incrementing the counter (on click, it uses `clerk-eval` to run code in the JVM environment)
(show-cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !counter inc))} "Number, go up!"])

;; Show the log using `pprint`:

(memo/defn-memo $log [!ref n]
  (xf/transform !ref (keep identity) (xf/sliding-window n)))

(show-cljs
 [:div.whitespace-pre-wrap.code.text-xs
  (with-out-str (pprint @($log (:!last-message @channel) 10)))])