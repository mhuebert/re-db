(ns re-db.reagent.local-state
  (:require [applied-science.js-interop :as j]
            [re-db.core :as db]
            [reagent.ratom :as ratom]
            [re-db.read :as read]
            [re-db.reagent :refer [read-index!]]
            [reagent.core :as reagent]
            [reagent.impl.component :refer [*current-component* component-name]])
  (:require-macros [re-db.reagent.local-state :as macros]))

;; problem: how to store local-state in re-db, to enable time-travel
;;
;; prototyped solution:
;;
;; 1. A "EAtom", entity-atom, wraps an entity to allow
;;    - reactive-read (via @ or lookup)
;;    - mutation (via reset!, which runs a transaction)
;; 2. We create our entity-atom using the `local-state` function. it accepts a :key parameter
;;    allowing us to differentiate instances of the same component. Default values can be
;;    passed.

;; an entity-atom always causes a dependency on the "whole entity"

(deftype EAtom [conn e defaults]
  IDeref
  (-deref [this]
    (merge defaults (read-index! conn :eav e)))
  ILookup
  (-lookup [this a]
    (get (read-index! conn :eav e) a (get defaults a)))
  (-lookup [this a not-found]
    (get (read-index! conn :eav e) a (get defaults a not-found)))
  IReset
  (-reset! [this new-value]
    (db/transact! conn [(assoc new-value :db/id e)]))
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs))))

;; a string key where we'll memoize the cursor per-component-instance
(def ratom-cache-key (str ::local-state))

;; memoize result of `initial-val-fn` on current ratom context
(defn ratom-memo [component key initial-val-fn]
  (let [!cache (j/get component ratom-cache-key (doto (volatile! {})
                                                  (->> (j/!set component ratom-cache-key))))]
    (or (@!cache key)
        (doto (initial-val-fn) (->> (vswap! !cache assoc key))))))

(defn local-state*
  "Return a local-state cursor for a Reagent component.
    :key      - to differentiate instances of this component
    :defaults - a map of default values (these will not be stored in re-db)"
  [conn & {:keys [key defaults component location]
           :or {component (reagent/current-component)
                key :singleton}}]

  (let [e {component key}]
    (ratom-memo component
                e
                (fn []
                  (ratom/add-on-dispose! ratom/*ratom-context* #(db/transact! conn [[:db/retractEntity e]]))
                  (EAtom. conn e defaults)))))