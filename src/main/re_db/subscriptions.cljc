(ns re-db.subscriptions
  "Subscriptions: named reactive computations cached globally for deduplication of effort"
  (:refer-clojure :exclude [def])
  (:require [re-db.reactive :as r :refer [add-on-dispose! dispose!]])
  #?(:cljs (:require-macros re-db.subscriptions)))

(defonce !subscription-defs (atom {}))
;; map of {svec, sub}
(defonce !subscription-cache (atom {}))

(defn subscription
  "Finds or creates a subscription for the given svec, a vector of [id & args]"
  ([svec not-found]
   (or (@!subscription-cache svec)
       (let [[id & args] svec]
         (when-let [init-fn (if (fn? id) id (@!subscription-defs id))]
           (let [sub (doto (apply init-fn args)
                       (->> (swap! !subscription-cache assoc svec)))]
             (assert (satisfies? r/ICompute sub) "Subscription function must return a reaction")
             (add-on-dispose! sub (fn [_]
                                    (swap! !subscription-cache dissoc svec)))
             sub)))
       not-found))
  ([svec]
   (or (subscription svec nil)
       (throw (ex-info (str "Subscription not found for " (first svec)) {:svec svec})))))

(defn sub
  "Returns current value of subscription for the given svec, a vector of [id & args]"
  [svec] @(subscription svec))

(defn register
  "Define a subscription by providing an id and constructor function, which should
   return a type that implements IDispose (clj) or IDisposable (cljs)"
  [id f]
  (let [pre-existing? (@!subscription-defs id)]
    (swap! !subscription-defs assoc id f)
    #?(:clj
       (when pre-existing? ;; update existing instances
         (doseq [[svec old-rx] @!subscription-cache
                 :when (= id (first svec))
                 :let [old-watches (r/get-watches old-rx)
                       oldval @old-rx]]
           (r/dispose! old-rx)
           (swap! !subscription-cache dissoc svec)
           (r/migrate-watches! old-watches oldval (subscription svec))))))

  id)

(defn clear-subscription-cache! []
  (try
    (doseq [sub (vals @!subscription-cache)] (when (satisfies? r/IDispose sub) (dispose! sub))))
  (swap! !subscription-cache empty) )

(defmacro def
  "Registers a subscription for name, returns subscription constructor function.

  Eg. in my-app.core,
  (s/def $my-sub (fn [x] ..))

  ($my-sub :foo) is the same as (subscribe ['my-app.core/$my-sub :foo])

   An id is made from `name`, qualified to the current namespace."
  ([name doc f]
   `(~'re-db.subscriptions/def ~name ~f))
  ([name f]
   (let [id (symbol (str *ns*) (str name))]
     `(do (register '~id ~f)
          (defn ~name [& args#] (subscription (into ['~id] args#)))))))

(comment
 (clear-subscription-cache!)

 (do
   (require '[re-db.hooks :as hooks])

   (register :sleeper
     (fn [& {:as options :keys [limit sleep] :or {limit 50 sleep 2000}}]
       (r/reaction
        (let [[counter count!] (hooks/use-state 0)
              instance (hooks/use-memo #(rand-int 1000))
              !future (hooks/use-memo #(atom nil))]
          (hooks/use-effect (fn []
                              (println (str "------ start: " instance (str " ------")))
                              #(do (println (str "------ stop:  " instance (str " ------\n")))
                                   (some-> @!future future-cancel))))
          (println (str "--- eval:     " [counter instance]))
          (when (< counter limit)
            (reset! !future
                    (future (Thread/sleep sleep)
                            (count! inc))))
          [counter instance])))))


 (do
   ;; adds watches (removes old watches)
   (remove-watch (subscription [:sleeper]) :w)
   (add-watch (subscription [:sleeper]) :w (fn [_ _ _ n] (println "--- watch:   " n)))

   (do (defonce R (atom nil))
       (some-> @R r/dispose!)
       (reset! R
               (r/reaction!
                (hooks/use-effect (fn []
                                    (println "--- reaction: init")
                                    #(println "--- reaction: dispose")))
                (println "--- reaction:" @(subscription [:sleeper]))))))

 (r/dispose! (subscription [:sleeper]))


 ;; Atoms as subscription sources...
 ;; verify that we can change which atom a subscription returns, and
 ;; - the value is immediately communicated to watches
 ;; - watches are updated when the subscription is redefined to another atom
 (def a (r/atom 0))
 (def b (r/atom 100))

 (register :atom2 (fn [] a))
 (register :atom2 (fn [] b))

 (swap! a inc)
 (swap! b inc)

 (add-watch (subscription [:atom2])
            :a
            (fn [_ _ _ n] (println "atom-sub:" n)))

 (r/dispose! (subscription [:atom2]))

 )

;; TODO - why is 'watch' called twice when redefining a subscription
;;  if we take out the manual notify-watches, then reactions aren't notified at all.



