(ns re-db.notebooks.test2
  (:require [mhuebert.clerk-cljs :refer [show-cljs]]))

(def !a (atom 1000))

(show-cljs (inc @!a))

(inc @!a)