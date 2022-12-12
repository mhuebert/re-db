(ns re-db.notebooks.test
  (:require [mhuebert.clerk-cljs :refer [show-cljs cljs]]
            [re-db.reactive :as r]
            [re-db.sync :as sync]))
(def !counter (atom 0))
(r/eager! (sync/$values !counter))