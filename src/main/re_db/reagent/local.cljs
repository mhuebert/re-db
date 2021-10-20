(ns re-db.reagent.local
  (:require [applied-science.js-interop :as j]
            [re-db.read :as read]
            [re-db.core :as db]
            [reagent.ratom :as ratom]
            [reagent.core :as reagent]
            [reagent.impl.component :refer [*current-component*]]))

;; problem: how to store local-state in re-db, to enable time-travel
;;
;; prototyped solution:
;;
;; 1. A "cursor" is a view of an entity which allows reactive-read (via @ or lookup)
;;    and mutation (via reset!, which runs a transaction) - so we can treat it like
;;    a familiar local-state ratom
;; 2. We create our cursor using the `local-state` function. it accepts a :key parameter
;;    allowing us to differentiate instances of the same component. Default values can be
;;    passed.

;; a cursor always causes a dependency on the "whole entity" - since the purpose
;; is local-component-state we don't need to differentiate attribute reads.

(deftype Cursor [conn e defaults]
  IDeref
  (-deref [this]
    (merge defaults (read/-e__ conn e)))
  ILookup
  (-lookup [this a]
    (get (read/-e__ conn e) a (get defaults a)))
  (-lookup [this a not-found]
    (get (read/-e__ conn e) a (get defaults a not-found)))
  IReset
  (-reset! [this new-value]
    (db/transact! conn [(assoc new-value :db/id e)]))
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs))))

;; a string key where we'll memoize the cursor per-component-instance
(def local-state-key (str ::cursor))

(defn local-state
  "Return a local-state cursor for a Reagent component.
    :key      - to differentiate instances of this component
    :defaults - a map of default values (these will not be stored in re-db)"
  [conn & {:keys [key defaults component]
           :or {component (reagent/current-component)
                key :singleton}}]

  ;; make a db-id out of the current component + provided key
  (let [e {(type *current-component*) key}]
    (j/get component local-state-key
           (let [cur (Cursor. conn e defaults)]
             (ratom/add-on-dispose! ratom/*ratom-context* #(db/transact! conn [[:db/retractEntity e]]

                                                                        ;; NOTE - not implemented - just a
                                                                        ;; reminder that these on-dispose retractions
                                                                        ;; are logically part of a previous transaction
                                                                        ;; but occur after a reagent render tick..
                                                                        ;; when implementing history/time-travel we
                                                                        ;; may want to consider this transaction part of
                                                                        ;; the previous transaction, possibly even batch these
                                                                        ;; transactions.
                                                                        {:merge-with-last? true}))
             (j/!set component local-state-key cur)
             cur))))
