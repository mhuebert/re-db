(ns re-db.reactive-test
  (:require [clojure.test :refer [deftest is]]
            [re-db.reactive :as r]
            [re-db.subscriptions :as s]
            [re-db.hooks :as hooks]))

(deftest subscriptions
  (s/clear-subscription-cache!)
  (let [a (r/atom 0)]
    (s/def $a (fn [] (r/reaction @a)))
    (s/register :a (fn [] (r/reaction @a)))
    (swap! a inc)
    (is (= @a @($a) @(s/subscription [:a])))))

(deftest disposal



  (let [!latch (atom 0)]
    (r/session
     @(r/reaction
       (hooks/use-effect
        (fn []
          (swap! !latch inc)
          #(swap! !latch inc)))))
    (is (= 2 @!latch)
        "use-effect hook is disposed")

    (s/clear-subscription-cache!)

    (let [!latch (atom 0)]
      (s/def $sub (fn []
                    (r/reaction
                     (hooks/use-effect
                      (fn []
                        (swap! !latch inc)
                        #(swap! !latch inc))))))
      (binding [r/*owner* nil]
        (let [v @(r/reaction
                  (hooks/use-effect
                   (fn []
                     (swap! !latch inc)
                     #(swap! !latch inc)))
                  1)]
          (is (= v 1) "Reaction without owner returns correct value")
          (is (= @!latch 2) "Reaction without owner is immediately disposed")))

      (r/reaction!
       (r/session @($sub))
       (is (= @!latch 4) "Session immediately disposes"))

      (let [rx (r/reaction (hooks/use-effect
                            (fn []
                              (swap! !latch inc)
                              #(swap! !latch inc))))]
        @rx
        @rx
        (is (= @!latch 8) "Same reaction can be instantiated multiple times"))

      (let [rx (r/reaction
                (let [[v v!] (hooks/use-volatile 0)]
                  (v! inc)))]
        (is (= @rx 1))
        (r/invalidate! rx)
        (is (= @rx 1)
            "Without an owner resent, a reaction disposes itself after every read.
            It begins again with fresh state.")

        (r/reaction!
         (is (= @rx 1))
         (r/invalidate! rx)
         (is (= @rx 2) "With an owner present, a reaction persists across time."))))))
