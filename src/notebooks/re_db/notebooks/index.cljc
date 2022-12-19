(ns re-db.notebooks.index
  "Root namespace for all cljc notebooks (including notebooks here is required for using cljs in the notebook)"
  (:require [nextjournal.clerk.viewer :as v]
            [re-db.notebooks.local]
            [re-db.notebooks.sync-editscript]
            [re-db.notebooks.sync-values]
            [re-db.notebooks.test]
            [re-db.notebooks.test2]
            [re-db.notebooks.tx-reactive]
            [re-db.notebooks.xform]
            [re-db.reactive :as r]))

;; TODO

;; Notebooks to work on:
;; entity-sync:   explain how re-db entities are shipped from server to client
;; entity-diff:   show how we send minimal diffs to the client & merge results in the local cache
;; pagination:    figure out how to do pagination in re-db
;; subscriptions: explain/demonstrate how subscriptions let us re-use cached reactions
;; xforms:        deep dive on using transducers with reactions/ratoms
;; hooks:         explain

;; exploration:
;; peer 2 peer:   try syncing data between browsers?
;; local storage / offline

;; auth authentication & authorization


;; Issues/topics:
;; - cleanup/garbage collection: how to more gracefully handle reaction lifecycles, especially in jvm
;;   (try disposing reactions on a timeout to avoid 'flickering'?)
;; - map vs vector for messages, keywords vs symbols for identifiers
;; - automatically register handlers via var metadata (like shadow arborist)?
;; - `handle` takes context as 1st arg, with :handlers map
;; - query cache lifecycle / "when we stop watching a query, do we clean up results?"
;; - threading / serious computing - how to handle subscriptions that do a lot of compute
;;   and slow down other parts of the app
;; - writing (vs reading)

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