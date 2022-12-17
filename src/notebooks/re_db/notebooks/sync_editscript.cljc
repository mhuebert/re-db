(ns re-db.notebooks.sync-editscript
  (:require [clojure.core.match :refer [match]]
            [clojure.pprint :refer [pprint]]
            [editscript.core :as editscript]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.integrations.reagent]
            [re-db.memo :as memo]
            [re-db.notebooks.tools.websocket :as ws]
            [re-db.reactive :as r]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; ## Diff and patch
;;
;; Let's improve syncing to send diffs instead of a full copy of data that changes.
;;
;; Using the [editscript](https://github.com/juji-io/editscript) library, we can write
;; a subscription that transforms a ref into a stream of "edits" which we can re-apply
;; on the client.

(memo/once
  (memo/defn-memo $edits [!ref]
    (xf/transform !ref
      (xf/before:after) ;; first turn the ref into [before, after] pairs
      (map (fn [[before after]]
             {:sync/editscript:patch (-> (editscript/diff before after)
                                         (editscript/get-edits))
              :sync/init {:value after}})))))

(def result-handlers
  {:sync/editscript:patch
   (fn [result edits]
     {:value (editscript/patch (:value result) (editscript/edits->script edits))})})

;; For an example, let's modify a list:

(defonce !list (atom (list 1 2 3 4 5)))

#?(:clj
   (defonce server
     (ws/serve {:port 9061
                :path "/ws"
                :resolve-ref (fn [context descriptor]
                               (match descriptor :list ($edits !list)))})))

(show-cljs
 (defonce channel (ws/connect {:port 9061
                               :path "/ws"
                               :result-handlers #'result-handlers})))

;; get rid of add-watch, remove-watch as global messages?


(show-cljs
 (let [result @(sync/$watch channel :list)]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                (render/inspect (:value result))])))

(show-cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !list conj (rand-int 20)))}
  "List, grow!"])

(memo/once
  (memo/defn-memo $log [!ref n]
    (xf/transform !ref (keep identity) (xf/sliding-window n))))

;; Message log:

(show-cljs
 [:div.whitespace-pre-wrap.code.text-xs
  (with-out-str (pprint @($log (:!last-message @channel) 10)))])

;; Note how we're only sending the full value once, and individual values on subsequent changes.