(ns re-db.reagent.local-state
  (:require [applied-science.js-interop :as j]
            [re-db.core :as db]
            [reagent.ratom :as ratom]
            [re-db.api :as api]
            [re-db.read :as read]
            [re-db.reagent :refer [read-index!]]
            [reagent.core :as reagent]
            [reagent.impl.component :refer [*current-component* component-name]])
  (:require-macros [re-db.reagent.local-state :as macros]))

;; an entity-atom always causes a dependency on the "whole entity"

(defn- resolve-conn [conn]
  (if (keyword? conn) (api/conn) conn))

(deftype EAtom [conn e default ^:mutable ^:volatile-mutable modified?]
  IDeref
  (-deref [this]
    (if ^boolean modified?
      (read-index! (resolve-conn conn) :eav ::local-state e)
      default))
  IReset
  (-reset! [this new-value]
    (set! modified? true)
    (db/transact! (resolve-conn conn) [[:db/add ::local-state e new-value]]))
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs))))

(defn snapshot
  "Read value of EAtom from a db snapshot (point-in-time)"
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
  [& {:keys [key default component location conn]
      :or {component (reagent/current-component)
           key :singleton
           conn ::api/conn}}]
  (let [e {location key}]
    (ratom/add-on-dispose! ratom/*ratom-context* #(do (prn :retracting-local-state e) (some-> (resolve-conn conn) (db/transact! [[:db/retract ::local-state e]]))))
    (EAtom. conn e default false)))