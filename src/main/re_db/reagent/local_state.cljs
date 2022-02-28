(ns re-db.reagent.local-state
  (:refer-clojure :exclude [read])
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

(deftype EAtom [conn e default ^:mutable ^:volatile-mutable modified?]
  IDeref
  (-deref [this]
    (if-some [v (read-index! conn :eav ::local-state e)]
      v
      default))
  IReset
  (-reset! [this new-value]
    (set! modified? true)
    (db/transact! conn [[:db/add ::local-state e new-value]]))
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs))))

(defn from-db
  "Read value of EAtom from a db (point-in-time)"
  [!state db]
  (get (db/get-entity db ::local-state) (.-e !state)))

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
    :default  - initial value"
  [conn & {:keys [key or component location]
           :or {component (reagent/current-component)
                key :singleton}}]
  (let [e {location key}]
    (ratom/add-on-dispose! ratom/*ratom-context* #(db/transact! conn [[:db/retract ::local-state e]]))
    (EAtom. conn {location key} or false)))