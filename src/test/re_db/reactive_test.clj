(ns re-db.reactive-test
  (:require [clojure.test :refer [deftest is]]
            [re-db.reactive :as r]
            [re-db.memo :as s]
            [re-db.hooks :as hooks]
            [re-db.xform :as xf]))


(deftest disposal

  (let [!latch (atom 0)]
    (r/session
     @(r/reaction
       (hooks/use-effect
        (fn []
          (swap! !latch inc)
          #(swap! !latch inc)))))
    (is (= 2 @!latch)
        "lazy (default) reaction is disposed"))
  (let [!latch (atom 0)]
    (r/session
     (def r (r/reaction!
             (hooks/use-effect
              (fn []
                (swap! !latch inc)
                #(swap! !latch inc))))))
    (is (= 1 @!latch)
        "independent reaction is not disposed")
    (r/dispose! r))

  (let [!latch (atom 0)]
    (def !latch (atom 0))
    (s/def-memo $sub
      (fn []
        (r/reaction
         (hooks/use-effect
          (fn []
            (swap! !latch inc)
            #(swap! !latch inc))))))

    (r/session @($sub))
    (is (= @!latch 2) "Session immediately disposes"))

  (let [!latch (atom 0)]
    (let [rx (r/reaction (hooks/use-effect
                          (fn []
                            (swap! !latch inc)
                            #(swap! !latch inc))))]
      (r/session @rx)
      (r/session @rx)
      (is (= @!latch 4) "Same reaction can be instantiated multiple times")))
  )

(defn try-eval
  "Evaluates f in a new thread with timeout"
  ([f] (try-eval f 2000))
  ([f ms]
   (let [!result (future
                  (try
                    (f)
                    (catch Exception e e)))]
     (let [result (deref !result ms (ex-info "timeout" {:ms ms}))]
       (if (instance? Exception result)
         (throw result)
         result)))))

(deftest glitch-tests
  (r/session
   (let [a (r/atom 1)
         b (r/reaction (+ @a 100))
         c (r/reaction (+ @b @a))
         c-values (xf/into [] c)]
     @c-values
     (swap! a inc)
     (is (= @c-values [102 104]))))

  (r/session
   (let [!a (r/atom 1.0)
         !rounded (r/reaction (Math/round @!a))
         !values (doto (xf/into [] !rounded) deref)]
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (is (= @!values [1 2])
         "evaluations occur in topological order, but changes
          do not propagate from unchanged nodes.")))

  (r/session
   (let [!a (r/atom 1)
         !b (doto (r/reaction @!a) deref)]
     (swap! !a inc)
     (prn @!a)))

  (r/session
   (let [a (r/atom 1)

         ;; depts of a
         b (r/reaction (* @a 2))

         c (r/reaction (* @a 20))
         cc (r/reaction (* @c 200))

         ;; f is first evaluated after `b` changes, while `e` has not been marked stale because it epends on `d` which is not an immediate dependent of `a`
         f (r/reaction (+ @b @cc))

         f-values (r/detach! (xf/into #{} f))]
     (swap! a inc)

     (is (= (count @f-values) 2) "glitches are avoided at depth")
     {:values @f-values}))

  (defn rev [m]
    (reduce-kv (fn [m k v]
                 (reduce #(update %1 %2 (fnil conj []) k) m v)) {} m))
  (rev {:a [:b :c]})
  (try-eval
   #(r/sorted-dependents :a (rev {:a []
                            :h [:g]
                            :c [:b :a]
                            :d [:c :a]
                            :e [:d :a]
                            :f [:a]
                            :g [:e]
                            })))

  (defn test-sort
    "Tests to see if sort-fn is a topological sort of the graph
     defined by root and get-dependents."
    [sort-fn root get-dependents]

    )

  ;;
  ;; evaluate in a thread with timeout

  )

