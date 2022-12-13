(ns re-db.notebooks.sync-editscript
  (:require [clojure.pprint :refer [pprint]]
            [editscript.core :as editscript]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [re-db.notebooks.sync-simple :as sync-simple]
            [re-db.subscriptions :as subs]
            [re-db.sync :as sync]
            [re-db.sync.client :as client]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;## Diff and patch
;
;Let's improve syncing to send diffs instead of a full copy of data that changes.
;
;Using the [editscript](https://github.com/juji-io/editscript) library, we can write
;a subscription that transforms a ref into a stream of "edits" which we can re-apply
;on the client. This requires making up a new operation, which we'll call `::sync/editscript.edits`

(subs/defonce $edits
              (fn [qvec !ref]
                (xf/transform !ref
                  (xf/before:after) ;; first turn the ref into [before, after] pairs
                  (map (fn [[before after]]
                         (-> [::sync/editscript.edits qvec (-> (editscript/diff before after)
                                                               (editscript/get-edits))]
                             ;; the ::sync/snapshot message is sent to clients immediately upon subscribing,
                             ;; to set initial state
                             (with-meta {::sync/snapshot [::sync/snapshot qvec after]})))))))



;; Using the websocket server from our `simple-sync` notebook,
;; add a handler for the `::sync/editscript.edits` operation.

(sync-simple/register ::sync/editscript.edits
  (fn [[_ qvec edits] _]
    (let [before (:value (client/read-result qvec))
          after (editscript/patch before (editscript/edits->script edits))]
      (db/transact! (client/set-result-tx qvec {:value after})))))


;; For an example, let's modify a map:

(defonce !list (atom ()))

(sync-simple/register :list
  (fn [qvec _] ($edits qvec !list)))

(show-cljs
 (let [result @(client/$watch sync-simple/channel [:list])]
   (cond (:loading? result) "loading..."
         (:error result) [:div "Error: " (:error result)]
         :else [:div.text-xl.bg-slate-600.text-white.inline-block.p-3.rounded
                (render/inspect (:value result))])))

(show-cljs
 [:button.p-2.rounded.bg-blue-100
  {:on-click #(render/clerk-eval '(swap! !list conj (rand-int 20)))}
  "List, grow!"])

;; Message log:

(show-cljs [:div.whitespace-pre-wrap.code.text-xs
            (with-out-str (pprint @sync-simple/!log))])