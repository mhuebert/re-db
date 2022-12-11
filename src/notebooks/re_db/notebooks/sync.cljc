^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync
  (:require [applied-science.js-interop :as j]
            [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [re-db.notebooks.tools.websocket :as websocket]
            [re-db.reactive :as r]
            [re-db.sync :as-alias sync]
            [re-db.sync.client :as client]
            [re-db.sync.server :as server]
            [re-db.sync.transit :as t]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; # Sync

;; This namespace demonstrates how to sync data from a server to client using
;; `re-db.sync.server` and `re-db.sync.client`.

;; We can sync any "watchable" thing, so let's use a plain atom.

(defonce !counter (atom 0))

;; Let's make a map of "syncable things" (refs) that we want to expose to clients.
;; Each ref will have a name that clients can use to request it, eg `:counter`:

(def refs-to-serve {:counter !counter})

;; Let's start server-side, and write a function to handle messages from the browser.

#?(:clj
   (defn handle-message [channel message]
     (let [[event ref-name] (t/unpack message)] ;; t/unpack deserializes a message using transit
       ;; See if we find a ref with the given name:
       (when-let [ref (refs-to-serve ref-name)]
         (let [!ref-tx (client/$ref-tx ref-name ref)]
           ;; $ref-tx transforms a ref into a stream of re-db transactions
           (case event
             ;; use re-db.sync.server's watch and unwatch functions, which watch refs and
             ;; handles sending updates to clients:
             ::sync/watch (server/watch-ref channel !ref-tx (fn [channel message]
                                                              (websocket/send! channel (t/pack message))))
             ::sync/unwatch (server/unwatch-ref channel !ref-tx)
             (println (str "Unknown event: " event))))))))


;; Start a websocket server:

#?(:clj
   (websocket/serve
    {:port 9060
     :path "/ws"
     :on-message handle-message
     :on-close (fn [channel status]
                 (server/unwatch-all channel))}))

;; ## Connect to the websocket server from the browser

(cljs
 (defonce ^js channel
   (websocket/connect {:url "ws://localhost:9060/ws"
                       :on-open (fn [channel]
                                  (client/on-open channel
                                    (fn [message]
                                      (websocket/send! channel (t/pack message)))))
                       :on-message (fn [channel message]
                                     (let [[event data] (t/unpack message)]
                                       (case event
                                         ::sync/tx (db/transact! data))))
                       :on-close client/on-close})))

;; Show our result:

(cljs
 (let [result @(client/$watch channel :counter)]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                [:span.font-bold (:value result)]])))

;; Modify the result - this swaps the counter item in our Clojure (JVM) environment,
;; which is then synced with the browser:
(cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !counter inc))}
  "Number go up!"])

;; Let's take a peek at the messages flowing into the browser's websocket channel:
(cljs
 (defonce !message-log
   (let [!atom (r/atom ())
         log-fn (j/fn [^:js {:keys [data]}]
                  (swap! !atom #(->> %
                                     (cons (t/unpack data))
                                     (take 10))))]
     (.addEventListener channel "message" log-fn)
     !atom)))

(cljs [:div.whitespace-pre-wrap.code.text-xs
       (with-out-str (pprint @!message-log))])

(comment
 (swap! !counter inc))

;; TODO

;; - diffing
;; - entities
