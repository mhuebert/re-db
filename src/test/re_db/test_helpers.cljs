(ns re-db.test-helpers
  (:require ["benchmark" :as bench]
            [applied-science.js-interop])
  (:require-macros re-db.test-helpers))

(aset js/window "Benchmark" bench)
(def Suite bench/Suite)