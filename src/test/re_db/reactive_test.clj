(ns re-db.reactive-test
  (:require [clojure.test :refer [deftest is]]
            [re-db.reactive :as r]
            [re-db.subscriptions :as s]))

(deftest subscriptions
  (s/clear-subscription-cache!)
  (let [a (r/atom 0)]
    (s/def $a (fn [] (r/reaction @a)))
    (s/register :a (fn [] (r/reaction @a)))
    (swap! a inc)
    (is (= @a @($a) @(s/subscription [:a])))))


