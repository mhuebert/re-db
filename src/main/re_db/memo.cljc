(ns re-db.memo
  "Subscriptions: named reactive computations cached globally for deduplication of effort"
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [re-db.reactive :as r :refer [add-on-dispose!]])
  #?(:cljs (:require-macros re-db.memo)))

;; memoize, but with reference counting (& lifecycle)

(defn- instance
  "Create a new instance to memoize"
  [!meta args]
  (when-let [sub (apply (:init-fn @!meta) args)]
    (add-on-dispose! sub (fn [_] (swap! !meta update :cache dissoc args)))
    sub))

(defn reset-fn!
  "Resets the init-fn for a def, "
  [!meta init-fn]
  (swap! !meta assoc :init-fn init-fn)
  (doseq [[args old-rx] (:cache @!meta)]
    (r/become old-rx (fn [old-val] (instance !meta args)))))

(defn constructor-fn [!meta]
  (with-meta (fn [& args]
               (or (get-in @!meta [:cache args])
                   (when-some [value (instance !meta args)]
                     (when (satisfies? r/ICountReferences value)
                       (swap! !meta assoc-in [:cache args] value))
                     value)))
             {::meta !meta}))

(defmacro fn-memo [& args]
  `(constructor-fn (atom {:cache {}
                          :init-fn (fn ~@args)})))

(defmacro present? [name]
  (if (:ns &env)
    `(~'exists? ~name)
    `(.hasRoot (def ~name))))

(defmacro def-memo
  "Defines a memoized function. If the return value implements re-db.reactive/ICountReferences,
   it will be removed from the memo cache when the last reference is removed."
  ([name f] `(re-db.memo/def-memo ~name nil ~f))
  ([name doc f]
   (assert (str/starts-with? (str name) "$") "A subscription's name must begin with $")
   `(do (declare ~name)
        (let [f# ~f
              !meta# (if (~'re-db.memo/present? ~name)
                       (::meta (meta ~name))
                       (atom {:cache {}
                              :init-fn f#}))]
          (reset-fn! !meta# f#)
          (def ~name (constructor-fn !meta#))))))

(defn clean-fn-args [args]
  (if (string? (first args))
    (rest args)
    args))

(defmacro defn-memo
  "Defines a memoized function. If the return value implements re-db.reactive/ICountReferences,
   it will be removed from the memo cache when the last reference is removed."
  [name & args]
  (let [args (clean-fn-args args)]
    `(def-memo ~name (fn ~name ~@args))))

(defn clear-memo! [memo]
  (let [!meta (::meta (meta memo))]
    (doseq [[args rx] (:cache @!meta)]
      (r/dispose! rx))
    (swap! !meta update :cache empty))
  memo)

(defmacro once
  "Like defonce for `def-memo` and `defn-memo`"
  [expr]
  (let [name (second expr)]
    `(when-not (present? ~name)
       ~expr)))

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
 (clear-memo! $inc)
 ;; session: init & dispose
 (r/session
  @($inc !a))

 (require '[re-db.hooks :as hooks])
 (def-memo $sleeper
   (fn [& {:as options :keys [limit sleep] :or {limit 50 sleep 2000}}]
     (r/reaction
      (let [[counter count!] (hooks/use-state 0)
            !future (hooks/use-memo #(atom nil))]
        (prn :compute counter)
        (hooks/use-effect (fn [] (prn :init counter)
                            #(do (prn :dispose counter)
                                 (some-> @!future future-cancel))))
        (when (< counter limit)
          (reset! !future (future (Thread/sleep sleep)
                                  (count! inc))))
        counter))))

 (clear-memo! $sleeper)
 ;; adds watches (removes old watches)
 (remove-watch ($sleeper) :w)
 (add-watch ($sleeper) :w (fn [_ _ _ n] (prn :watch n)))
 @($sleeper))



