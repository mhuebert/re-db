(ns re-db.reactive.hooks
  (:require [re-db.util :as util]
            [re-db.reactive :as r]))

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
   (let [!hook (r/get-next-hook! :effect)
         hook @!hook]
     (when (or (r/fresh? hook) (not= (:prev-deps hook) deps))
       (some-> (:dispose hook) eval-fn)
       (let [dispose (util/guard (r/without-deref-capture (f)) fn?)]
         (vswap! !hook assoc :dispose dispose :prev-deps deps)))
     nil)))

(defn use-memo
  "Within a reaction, returns the (cached) result of evaluating f.
   f is called once to initialize, then re-evaluated when `deps` change."
  ([f] (use-memo f nil))
  ([f deps]
   (let [!hook (r/get-next-hook! :memo)
         hook @!hook]
     (if (or (r/fresh? hook) (not= (:prev-deps hook) deps))
       (let [value (r/without-deref-capture (eval-fn f))]
         (vswap! !hook assoc :value value :prev-deps deps)
         value)
       (:value @!hook)))))

(defn use-state [initial-state]
  (let [!hook (r/get-next-hook! :state)
        owner r/*owner*]
    (when (r/fresh? @!hook)
      (vswap! !hook assoc
              :value (r/without-deref-capture (eval-fn initial-state))
              :update-fn (fn [value]
                           (let [new-value (if (fn? value)
                                             (let [old-value (:value @!hook)]
                                               (r/without-deref-capture (value old-value)))
                                             value)]
                             (vswap! !hook assoc :value new-value)
                             (r/invalidate! owner)
                             new-value))))
    ((juxt :value :update-fn) @!hook)))

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