(ns re-db.notebooks.index
  "Root namespace for all cljc notebooks (including notebooks here is required for using cljs in the notebook)"
  (:require [nextjournal.clerk.viewer :as v]
            [re-db.notebooks.local]
            [re-db.notebooks.sync-editscript]
            [re-db.notebooks.sync-simple]
            [re-db.notebooks.test]
            [re-db.notebooks.tx-reactive]
            [re-db.notebooks.xform]
            [re-db.reactive :as r]))

;; TODO

;; entity-sync:   explain how re-db entities are shipped from server to client
;; entity-diff:   show how we send minimal diffs to the client & merge results in the local cache
;; pagination:    figure out how to do pagination in re-db
;; subscriptions: explain/demonstrate
;; xforms:        deep dive on using transducers with reactions/ratoms

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