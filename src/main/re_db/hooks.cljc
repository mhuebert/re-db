(ns re-db.hooks
  (:require #?(:cljs ["react" :as react])
            #?(:cljs["use-sync-external-store/shim" :refer [useSyncExternalStore]])
            [applied-science.js-interop :as j]
            [re-db.impl.hooks :as -hooks]
            [re-db.reactive :as r :refer [*owner*]]
            [re-db.util :as util])
  #?(:cljs (:require-macros [re-db.hooks :refer [if-react]])))

(defmacro if-react [then else]
  (if (:ns &env)
    `(if-not *owner* ~then ~else)
    else))

#?(:cljs
   (defn- cljs-deps [x] (cond-> x (not (array? x)) to-array)))

;; TODO
;; if not in a re-db context, we should use the react hooks directly.
;; this means re-db & react hooks should be ~equivalent.

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
    (-nth [this i nf] (case i 0 value 1 update-fn nf))
    ISeqable
    (-seq [this] (list value update-fn))))

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
   (if-react
    (react/useEffect f (cljs-deps deps))
    (when-let [owner *owner*]
      (let [[i hook new?] (-hooks/get-next-hook! owner :use-effect)]
        (when (or new? (not= (:hook/prev-deps hook) deps))
          (some-> (:hook/dispose hook) eval-fn)
          (-hooks/update-hook! owner i dissoc :hook/dispose) ;; remove this?
          (let [dispose (util/guard (r/without-deref-capture (f)) fn?)]
            (-hooks/update-hook! owner i assoc :hook/dispose dispose :hook/prev-deps deps)))
        nil)))))

(defn use-on-dispose
  ([f] (use-on-dispose f nil))
  ([f deps]
   (use-effect (constantly f) deps)))

(defn use-memo
  "Within a reaction, returns the (cached) result of evaluating f.
   f is called once to initialize, then re-evaluated when `deps` change."
  ([f] (use-memo f nil))
  ([f deps]
   (if-react
    (react/useMemo f (cljs-deps deps))
    (if-let [owner *owner*]
      (let [[i hook new?] (-hooks/get-next-hook! owner :use-memo)]
        (:hook/value
          (if (or new? (not= (:hook/prev-deps hook) deps))
            (-hooks/update-hook! owner i assoc
                                 :hook/value (r/without-deref-capture (eval-fn f))
                                 :hook/prev-deps deps)
            hook)))
      (f)))))

(defn use-state [initial-state]
  (if-react
   (react/useState initial-state)
   (if-let [owner *owner*]
     (let [[i hook new?] (-hooks/get-next-hook! owner :use-state)]
       (atom-like
         (if new?
           (-hooks/update-hook! owner i assoc
                                :hook/value (r/without-deref-capture (eval-fn initial-state))
                                :hook/update-fn (fn [value]
                                                  (let [old-value (:hook/value (-hooks/get-hook owner i))
                                                        new-value (if (fn? value)
                                                                    (r/without-deref-capture (value old-value))
                                                                    value)]
                                                    (-hooks/update-hook! owner i assoc :hook/value new-value)
                                                    (r/compute! owner)
                                                    new-value)))
           hook)))
     (atom-like (eval-fn initial-state) nil))))

(defn use-reducer [f init]
  (if-react
   (react/useReducer f init)
   (let [[value update-fn] (use-state init)]
     (atom-like value (fn [v] (update-fn #(f % v)))))))

#?(:cljs
   (deftype Ref [current]
     IDeref
     (-deref [^js this] current)
     IReset
     (-reset! [^js this new-value]
      ;; we must use the (.-current ^js this) form here to ensure that
      ;; `current` is not renamed by the closure compiler
      ;; (react needs to see it)
       (set! (.-current ^js this) new-value))
     ISwap
     (-swap! [o f] (reset! o (f o)))
     (-swap! [o f a] (reset! o (f o a)))
     (-swap! [o f a b] (reset! o (f o a b)))
     (-swap! [o f a b xs] (reset! o (apply f o a b xs)))))

(defn use-ref [initial-state]
  (if-react
   (react/useCallback (Ref. (eval-fn initial-state)) (j/lit []))
   (if-let [owner *owner*]
     (let [[i hook new?] (-hooks/get-next-hook! owner :use-ref)]
       (atom-like
         (if new?
           (-hooks/update-hook! owner i assoc
                                :hook/value (r/without-deref-capture (eval-fn initial-state))
                                :hook/update-fn (fn [value]
                                                  (let [old-value (:hook/value (-hooks/get-hook owner i))
                                                        new-value (if (fn? value)
                                                                    (r/without-deref-capture (value old-value))
                                                                    value)]
                                                    (-hooks/update-hook! owner i assoc :hook/value new-value)
                                                    new-value)))
           hook)))
     (atom-like (eval-fn initial-state) nil))))

(defn use-deref
  "Returns deref'd value of atom, and re-renders parent when the atom notifies watches."
  [ref]
  (if-react
   (let [id (react/useCallback (j/lit {}))]
     (useSyncExternalStore
      (react/useCallback
       (fn [changed!]
         (add-watch ref id (fn [_ _ _ _] (changed!)))
         #(remove-watch ref id))
       (j/lit [ref]))
      #(deref ref)))
   (let [[_ set-value!] (use-state (constantly (r/peek ref)))]
     (use-effect
      (fn []
        (add-watch ref set-value! (fn [_ _ _ new-value]
                                    (set-value! (constantly new-value))))
        #(remove-watch ref set-value!)))
     (r/peek ref))))

(defmacro with-let
  "Within a reaction, evaluates bindings once, memoizing the results."
  [bindings & body]
  (let [finally (some-> (last body)
                        (util/guard #(and (list? %) (= 'finally (first %))))
                        rest)
        body (cond-> body finally drop-last)]
    `(let [~@(mapcat (fn [[sym value]]
                       [sym `(first (use-state (fn [] ~value)))])
                     (partition 2 bindings))]
       ~(when (seq finally)
          `(use-effect (fn [] (fn [] ~@finally))))
       ~@body)))