(ns re-db.subscriptions
  "Subscriptions: named reactive computations cached globally for deduplication of effort"
  (:refer-clojure :exclude [def])
  (:require [re-db.reactive :as r :refer [add-on-dispose! dispose!]])
  #?(:cljs (:require-macros re-db.subscriptions)))

(defonce !subscription-defs (atom {}))
(defonce !subscription-cache (atom {}))

(defn subscription
  "Finds or creates a subscription for the given svec, a vector of [id & args]"
  [svec]
  (or (@!subscription-cache svec)
      (let [[id & args] svec
            init-fn (if (fn? id) id (@!subscription-defs id))
            _ (when-not init-fn
                (prn (str "Subscription not defined: " id) svec))
            sub (apply init-fn args)]
        (add-on-dispose! sub (fn [_] (swap! !subscription-cache dissoc svec)))
        (swap! !subscription-cache assoc svec sub)
        sub)))

(defn sub
  "Returns current value of subscription for the given svec, a vector of [id & args]"
  [svec] @(subscription svec))

(defn register
  "Define a subscription by providing an id and constructor function, which should
   return a type that implements IDispose (clj) or IDisposable (cljs)"
  ([id f make-new-f]
   ;; registers fn, also updates any existing subscriptions
   (register id f)
   #?(:clj
      (doseq [[svec reaction] @!subscription-cache
              :when (= id (first svec))
              :let [new-f (apply make-new-f (rest svec))]]
        (r/reset-function! reaction new-f)))
   id)
  ([id f]
   (swap! !subscription-defs assoc id f)
   id))

(defn clear-subscription-cache! []
  (doseq [sub (vals @!subscription-cache)] (dispose! sub)))

(defmacro def
  "Registers a subscription for name, returns subscription constructor function.

  Eg. in my-app.core,
  (s/def $my-sub (fn [x] ..))

  ($my-sub :foo) is the same as (subscribe ['my-app.core/$my-sub :foo])

   An id is made from `name`, qualified to the current namespace."
  [name f]
  (let [id (symbol (str *ns*) (str name))]
    `(do (register '~id ~f)
         (defn ~name [& args#] (subscription (into ['~id] args#))))))

(comment
 (clear-subscription-cache!))