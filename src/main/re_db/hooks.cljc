(ns re-db.hooks
  (:require [re-db.impl.hooks :as -hooks :refer [*hook-i*]]
            [re-db.reactive :as r :refer [*owner*]]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.hooks)))


(util/support-clj-protocols
  (deftype AtomLike
    ;; gives hooks an atom-like interface.
    [value update-fn]
    IDeref (-deref [_] value)
    IReset (-reset! [_ new-value] (update-fn (constantly new-value)) new-value)
    ISwap
    (-swap! [_ f] (update-fn f))
    (-swap! [_ f a] (update-fn #(f % a)))
    (-swap! [_ f a b] (update-fn #(f % a b)))
    (-swap! [_ f a b xs] (update-fn #(apply f % a b xs)))
    IIndexed
    (-nth [this i] (case i 0 value 1 update-fn))
    (-nth [this i nf] (case i 0 value 1 update-fn nf))))

(defn atom-like
  ([m] (AtomLike. (:hook/value m) (:hook/update-fn m)))
  ([value update-fn] (->AtomLike value update-fn)))

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
         [i hook new?] (-hooks/get-next-hook! owner :use-state)]
     (when (or new? (not= (:hook/prev-deps hook) deps))
       (some-> (:hook/dispose hook) eval-fn)
       (-hooks/update-hook! owner i merge {:initialized? true :hook/dispose nil})
       (let [dispose (util/guard (r/without-deref-capture (f)) fn?)]
         (-hooks/update-hook! owner i merge {:hook/dispose dispose :hook/prev-deps deps})))
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
         [i hook new?] (-hooks/get-next-hook! owner :use-state)]
     (:hook/value
      (if (or new? (not= (:prev-deps hook) deps))
        (-hooks/update-hook! owner i merge {:hook/value (r/without-deref-capture (eval-fn f))
                                            :prev-deps deps})
        hook)))))

(defn use-state [initial-state]
  (let [owner *owner*
        [i hook new?] (-hooks/get-next-hook! owner :use-state)]

    (atom-like
     (assoc (if new?
              (-hooks/update-hook! owner i merge
                {:hook/value (r/without-deref-capture (eval-fn initial-state))})
              hook)
       :hook/update-fn (fn [value]
                         (let [old-value (:hook/value (-hooks/get-hook owner i))
                               new-value (if (fn? value)
                                           (r/without-deref-capture (value old-value))
                                           value)]
                           (-hooks/update-hook! owner i assoc :hook/value new-value)
                           (when (not= old-value new-value)
                             (r/compute! owner))
                           new-value))))))

(defn use-reducer [f init]
  (let [[value update-fn] (use-state init)]
    (atom-like value (fn [v] (update-fn #(f % v))))))

(defn use-volatile [initial-state]
  (let [owner *owner*
        [i hook new?] (-hooks/get-next-hook! owner :use-state)]
    (atom-like
     (assoc (if new?
              (-hooks/update-hook! owner i merge
                {:hook/value (r/without-deref-capture (eval-fn initial-state))})
              hook)
       :hook/update-fn (fn [value]
                         (let [old-value (:hook/value (-hooks/get-hook owner i))
                               new-value (if (fn? value)
                                           (r/without-deref-capture (value old-value))
                                           value)]
                           (-hooks/update-hook! owner i assoc :hook/value new-value)
                           new-value))))))

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

(defn use-deref
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

(defmacro named [name & expr]
  `(let [v# (do ~@expr)]
     (-hooks/update-hook! r/*owner* @-hooks/*hook-i*
       (fn [m#]
         (-> m#
             (assoc :hook/name ~name)
             (update :hook/evaluations (fnil inc 0)))))
     v#))

(defn current []
  (-hooks/get-hook r/*owner* @-hooks/*hook-i*))