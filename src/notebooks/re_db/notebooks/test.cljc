(ns re-db.notebooks.test
  (:require [mhuebert.clerk-cljs :refer [show-cljs]]))

(def !a (atom "test"))

(show-cljs @!a)