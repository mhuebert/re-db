(ns re-db.notebooks.sync-edits
  (:require [clojure.pprint :refer [pprint]]
            [editscript.core :as editscript]
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

;; Using the [editscript](https://github.com/juji-io/editscript) library, we transform a
;; ref into a stream of edits:

(memo/defn-memo $edits [!ref]
  (xf/transform !ref
    (xf/before:after) ;; first turn the ref into [before, after] pairs
    (map (fn [[before after]]
           {::sync/editscript (-> (editscript/diff before after)
                                  (editscript/get-edits))
            ::sync/init {:value after}}))))

;; In the browser, we'll need an extra result-handler which handles editscript edits.
;; Result handlers are reducing functions which receive the previous value (typically
;; a map of :value or :error) and return a new result.

(defn handle-editscript-result [prev edits]
  {:value (editscript/patch
           (:value prev)
           (editscript/edits->script edits))})

;; A websocket server (clj, runs on the jvm):
#?(:clj
   (def server
     (ws/serve :port 9061
               :handlers (merge
                          (sync/query-handlers {:list ($edits !list)})
                          {:conj! (fn [_] (swap! !list conj (rand-int 100)))}))))

;; A websocket channel (cljs, runs in the browser):
(show-cljs
  (def channel
    (ws/connect :port 9061
                :handlers (sync/result-handlers
                           {::sync/editscript handle-editscript-result}))))


;; Show the result of watching `:list` (as exposed in `!refs`):
(show-cljs
  (let [result @(sync/$query channel :list)]
    (render/inspect
     (:value result result))))

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
   (with-out-str (pprint @($log (:!last-message channel) 10)))])