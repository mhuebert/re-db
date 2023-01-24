(ns re-db.subscriptions
  "Registry of named subscriptions (memoized reactive computations)"
  (:require
   [re-db.memo :as memo]
   [re-db.reactive :as r])
  #?(:cljs (:require-macros re-db.subscriptions)))

(defonce !subscriptions (atom {}))

(defn subscription
  "Finds or creates a subscription for the given svec, a vector of [id & args]"
  [[id & args]]
  (if-let [init-fn (@!subscriptions id)]
    (apply init-fn args)
    (throw (ex-info (str "Subscription not defined: " id) {:id id :args args}))))

(defn register
  "Define a subscription by providing an id and constructor function, which should
   return a type that implements IDispose (clj) or IDisposable (cljs)"
  [id f]
  (if-let [memoized (@!subscriptions id)]
    (memo/reset-fn! memoized f)
    (swap! !subscriptions assoc id (memo/memoize f)))
  id)

(defn clear-subscription-cache! []
  (doseq [memoized (vals @!subscriptions)] (memo/dispose! memoized))
  (swap! !subscriptions empty))

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



