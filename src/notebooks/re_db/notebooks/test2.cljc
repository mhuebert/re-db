(ns re-db.notebooks.test2
  (:require [mhuebert.clerk-cljs :refer [show-cljs]]))

(def !a (atom "test2"))

(show-cljs @!a)