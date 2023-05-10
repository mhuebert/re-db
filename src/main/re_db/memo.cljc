(ns re-db.memo
  "Subscriptions: named reactive computations cached globally for deduplication of effort"
  (:refer-clojure :exclude [memoize])
  (:require [clojure.string :as str]
            [re-db.reactive :as r :refer [add-on-dispose!]]
            [re-db.util :refer [sci-macro]])
  #?(:cljs (:require-macros re-db.memo)))

;; memoize, but with reference counting (& lifecycle)

(defn- new-entry!
  "Adds an entry to the cache, with a dispose hook to remove when unwatched"
  [!state args]
  (let [value (apply (:init-fn @!state) args)]
    (when (and value (satisfies? r/IReactiveValue value))
      (swap! !state assoc-in [:cache args] value)
      (add-on-dispose! value (fn [_] (swap! !state update :cache dissoc args))))
    value))

(defn get-state [f] (::state (meta f)))

(defn reset-fn!
  "Resets the init-fn for a memoized function"
  [f init-fn]
  (let [!state (get-state f)]
    (swap! !state assoc :init-fn init-fn)
    (doseq [[args old-rx] (:cache @!state)]
      (try (r/become old-rx (apply init-fn args))
           ;; if we encounter an error while updating a subscription function (eg. ArityException when
           ;; making changes to the init-fn), fall back to removing it from the cache and passing the error
           ;; to the old-rx.
           (catch #?(:clj Exception :cljs js/Error) e
             (swap! !state update :cache dissoc args)
             (reset! old-rx e))))
    f))

(defn constructor-fn [!state]
  (with-meta (fn [& args]
               (or (get-in @!state [:cache args])
                   (new-entry! !state args)))
             {::state !state}))

(defn memoize [f]
  (constructor-fn (atom {:cache {}
                         :init-fn f})))

(sci-macro
  (defmacro fn-memo [& args]
    `(memoize (fn ~@args))))

(sci-macro
  (defmacro def-memo
    "Defines a memoized function. If the return value implements re-db.reactive/IReactiveValue,
     it will be removed from the memo cache when the last reference is removed."
    ([name doc f] `(re-db.memo/def-memo ~(with-meta name {:doc doc}) ~f))
    ([name f]
     (assert (str/starts-with? (str name) "$") "A subscription's name must begin with $")
     `(do (defonce ~name (memoize nil))
          (reset-fn! ~name ~f)
          ~name))))

(defn- without-docstring [args] (cond-> args (string? (first args)) rest))

(sci-macro
  (defmacro defn-memo
    "Defines a memoized function. If the return value implements re-db.reactive/IReactiveValue,
     it will be removed from the memo cache when the last reference is removed."
    [name & args]
    `(def-memo ~name (fn ~name ~@(without-docstring args)))))

(defn dispose! [memoized]
  (let [!state (::state (meta memoized))]
    (doseq [[args rx] (:cache @!state)]
      (r/dispose! rx))
    (swap! !state update :cache empty))
  memoized)

(sci-macro
  (defmacro once
    "Like defonce for `def-memo` and `defn-memo`"
    [expr]
    (let [name (second expr)]
      `(when-not (r/var-present? ~name)
         ~expr))))

(comment


 (def !a (r/atom 0))
 (defn-memo $inc
   [!ref]
   (r/reaction
     (prn :compute)
     (re-db.hooks/use-effect
      (fn [] (prn :init) #(prn :dispose)))
     @!ref))

 ;; deref: activates
 @($inc !a)
 ;; swap: recomputes
 (swap! !a inc)
 ;; clear: disposes
 (dispose! $inc)
 ;; session: init & dispose
 (r/session
  @($inc !a))

 (require '[re-db.hooks :as hooks])
 (defn-memo $sleeper [& {:as options
                         :keys [limit sleep]
                         :or {limit 50 sleep 2000}}]
   (r/reaction
     (let [!counter (hooks/use-state 0)
           !future (hooks/use-memo #(atom nil))]
       (prn :compute--- @!counter)
       (hooks/use-effect
        (fn []
          (prn :init @!counter)
          #(do (prn :dispose @!counter)
               (some-> @!future future-cancel))))
       (when (< @!counter limit)
         (reset! !future (future (Thread/sleep sleep)
                                 (swap! !counter inc))))
       @!counter)))


 ;; adds watches (removes old watches)
 (add-watch ($sleeper) :w (fn [_ _ _ n] (prn :watch n)))
 (remove-watch ($sleeper) :w)
 (dispose! $sleeper)
 )