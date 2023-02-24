(ns re-db.sync.editscript
  (:require [re-db.memo :as memo]
            [re-db.xform :as xf]
            [re-db.sync :as sync]
            [editscript.core :as editscript]))


;; Using the [editscript](https://github.com/juji-io/editscript) library, we transform a
;; ref into a stream of edits:

(memo/defn-memo $edits [!ref]
  (xf/transform !ref
    (xf/before:after) ;; first turn the ref into [before, after] pairs
    (map (fn [[before after]]
           {::editscript (-> (editscript/diff before after)
                             (editscript/get-edits))
            ::sync/init {:value after}}))))

;; In the browser, we'll need an extra result-handler which handles editscript edits.
;; Result handlers are reducing functions which receive the previous value (typically
;; a map of :value or :error) and return a new result.

(def result-handlers
  {::editscript (fn [prev edits]
                  {:value (editscript/patch
                           (:value prev)
                           (editscript/edits->script edits))})}) 