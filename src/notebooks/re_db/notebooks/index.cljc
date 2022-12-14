(ns re-db.notebooks.index
  (:require [nextjournal.clerk.viewer :as v]
            [re-db.notebooks.local]
            [re-db.notebooks.sync-editscript]
            [re-db.notebooks.sync-simple]
            [re-db.notebooks.test]
            [re-db.notebooks.tx-reactive]
            [re-db.notebooks.xform]
            [re-db.reactive :as r]))



;; TODOs

;; - entity sync: sending re-db entity references across the wire
;; - reactive db queries (which invalidate based on a tx-log)
;; - paginating queries
;; - subscriptions: what they are, how they work
;; - xforms: ratoms and reactions as streams with transducers

(comment
 #_(v/reset-viewers! :default
                     (v/add-viewers v/default-viewers
                                    [{:pred #?(:clj  #(instance? clojure.lang.IDeref %)
                                               :cljs #(satisfies? IDeref %))
                                      :transform-fn (v/update-val
                                                     (fn [ideref]
                                                       (v/with-viewer :tagged-value
                                                                      {:tag "object"
                                                                       :value (vector (symbol (pr-str (type ideref)))
                                                                                      (r/peek ideref))})))}])))