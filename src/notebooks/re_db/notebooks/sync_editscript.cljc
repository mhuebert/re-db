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

;; ## Diff and patch
;;
;; Let's improve syncing to send diffs instead of a full copy of data that changes.
;;
;; Using the [editscript](https://github.com/juji-io/editscript) library, we can write
;; a subscription that transforms a ref into a stream of "edits" which we can re-apply
;; on the client.
;;
;; Below we define a subscription, `$edits`, which takes a stream of values and creates
;; diffs between each successive pair. The diff is wrapped as a message, `[::sync/editscript.edits X]`,
;; which we'll have to implement in the client.

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

;; Each diff contains `::sync/snapshot` metadata. This will be sent when a client first starts watching
;; the stream, to set its initial value.

;; Here is the handler for `::sync/editscript.edits`:

(sync-simple/register ::sync/editscript.edits
  (fn [[_ qvec edits] _]
    (let [before (:value (client/read-result qvec))
          after (editscript/patch before (editscript/edits->script edits))]
      (db/transact! (client/set-result-tx qvec {:value after})))))


;; For an example, let's modify a list:

(defonce !list (atom (list 1 2 3 4 5)))

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

;; Note how we're only sending the full value once, and individual values on subsequent changes.