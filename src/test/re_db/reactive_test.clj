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

(deftest diamond-pattern-glitch-test
  (r/session
   (let [!a (r/atom 1)
         !b (r/reaction (+ @!a 1))
         !c-called (atom 0)
         !c (r/reaction! (swap! !c-called inc) (+ @!a @!b))
         !c-values (r/detach! (xf/into #{} !c))]
     (swap! !a inc)
     (is (= @!c-called 2) "refs are evaluated once per epoch")
     (is (= @!c-values #{3 5})))))

