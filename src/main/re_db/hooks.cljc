(ns re-db.hooks
  (:require [re-db.util :as util]
            [re-db.reactive :as r :refer [*owner*]]
            [re-db.impl.hooks :as -hooks :refer [*hook-i*]]))

(defn eval-fn
  ([f] (if (fn? f) (f) f))
  ([f x] (if (fn? f) (f x) f)))

(defn use-effect
  "Within a reaction, evaluates f once (or when `deps` changes).
   f may return a 'dispose' function, which is called when the
   reaction is disposed and when deps (optional) change.

   Returns nil."
  ([f] (use-effect f nil))
  ([f deps]
   (let [owner *owner*
         hook (-hooks/get-next-hook! owner :state)
         i @*hook-i*]
     (when (or (-hooks/fresh? hook) (not= (:prev-deps hook) deps))
       (some-> (:dispose hook) eval-fn)
       (-hooks/update-hook! owner i merge {:initialized? true :dispose nil})
       (let [dispose (util/guard (r/without-deref-capture (f)) fn?)]
         (-hooks/update-hook! owner i merge {:dispose dispose :prev-deps deps})))
     nil)))

(defn use-on-dispose
  ([f] (use-on-dispose f nil))
  ([f deps]
   (use-effect (constantly f) deps)))

(defn use-memo
  "Within a reaction, returns the (cached) result of evaluating f.
   f is called once to initialize, then re-evaluated when `deps` change."
  ([f] (use-memo f nil))
  ([f deps]
   (let [owner *owner*
         hook (-hooks/get-next-hook! owner :state)
         i @*hook-i*]
     (:value
      (if (or (-hooks/fresh? hook) (not= (:prev-deps hook) deps))
        (-hooks/update-hook! owner i merge {:value (r/without-deref-capture (eval-fn f))
                                            :prev-deps deps})
        hook)))))

(defn use-state [initial-state]
  (let [owner *owner*
        hook (-hooks/get-next-hook! owner :state)
        i @*hook-i*]
    ((juxt :value :update-fn)
     (if (-hooks/fresh? hook)
       (-hooks/update-hook! owner i merge
         {:value (r/without-deref-capture (eval-fn initial-state))
          :update-fn (fn [value]
                       (let [old-value (:value (-hooks/get-hook owner i))
                             new-value (if (fn? value)
                                         (r/without-deref-capture (value old-value))
                                         value)]
                         (-hooks/update-hook! owner i assoc :value new-value)
                         (when (not= old-value new-value)
                           (r/compute! owner))
                         new-value))})
       hook))))

(defn use-reducer [f init]
  (let [[state set-state!] (use-state init)]
    [state (fn [v] (set-state! (f state v)))]))

(defn use-volatile [initial-state]
  (let [owner *owner*
        hook (-hooks/get-next-hook! owner :state)
        i @*hook-i*]
    ((juxt :value :update-fn)
     (if (-hooks/fresh? hook)
       (-hooks/update-hook! owner i merge
         {:value (r/without-deref-capture (eval-fn initial-state))
          :update-fn (fn [value]
                       (let [old-value (:value (-hooks/get-hook owner i))
                             new-value (if (fn? value)
                                         (r/without-deref-capture (value old-value))
                                         value)]
                         (-hooks/update-hook! owner i assoc :value new-value)
                         new-value))})
       hook))))

(defn use-watch
  "Returns [old-value, new-value] when ref changes - initially [nil, value]"
  [ref]
  (let [[value set-value!] (use-state [nil @ref])]
    (use-effect
     (fn []
       (add-watch ref set-value! (fn [_ _ old-value new-value]
                                   (set-value! [old-value new-value])))
       #(remove-watch ref set-value!)))
    value))

(defn use-atom
  "Returns deref'd value of atom, and re-renders parent when the atom notifies watches."
  [ref]
  (let [[value set-value!] (use-state (r/peek ref))]
    (use-effect
     (fn []
       (add-watch ref set-value! (fn [_ _ _ new-value]
                                   (set-value! new-value)))
       #(remove-watch ref set-value!)))
    value))

(defmacro with-let
  "Within a reaction, evaluates bindings once, memoizing the results."
  [bindings & body]
  (let [finally (some-> (last body)
                        (util/guard #(and (list? %) (= 'finally (first %))))
                        rest)
        body (cond-> body finally drop-last)]
    `(let [~@(mapcat (fn [[sym value]]
                       [sym `(use-memo (fn [] ~value))])
                     (partition 2 bindings))]
       ~(when (seq finally)
          `(use-effect (fn [] (fn [] ~@finally))))
       ~@body)))