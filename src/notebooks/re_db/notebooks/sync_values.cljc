^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ns re-db.notebooks.sync-values
  (:require [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.integrations.reagent]
            [re-db.memo :as memo]
            [re-db.notebooks.tools.websocket :as ws]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; In this namespace we'll sync the contents of an atom, !list.
(defonce !list (atom ()))

;; $values is a memoized transform of a ref which wraps values in a {:value _} map.

(memo/defn-memo $values [ref]
  (xf/transform ref
    (map (fn [v] {:value v}))))

(show-cljs
  (let [my-atom (atom 1)]
    {:atom @my-atom
     :$values @($values my-atom)}))

;; A websocket server (clj, runs on the jvm):
#?(:clj
   (def server
     (ws/serve {:port 9060
                :handlers (merge
                           (sync/watch-handlers :resolve-ref {:list ($values !list)})
                           {:conj! (fn [_] (swap! !list conj (rand-int 100)))})})))

;; A websocket channel (cljs, runs in the browser):
(show-cljs
  (defonce channel
    (ws/connect {:port 9060
                 :handlers (sync/watch-handlers)})))

;; Show the result of watching `:list` (as exposed in `!refs`):
(show-cljs
  (let [result @(sync/$watch channel :list)]

    (render/inspect (or (:value result) result))))

;; Modify the list:
(show-cljs
  [:button.p-2.rounded.bg-blue-100
   {:on-click #(sync/send channel [:conj!])}
   "List, grow!"])

(memo/defn-memo $log [!ref n]
  (xf/transform !ref (keep identity) (xf/sliding-window n)))

;; Show a log of events:
(show-cljs
  [:div.whitespace-pre-wrap.code.text-xs
   (with-out-str (pprint @($log (:!last-message @channel) 10)))])