(ns re-db.notebooks.xform
  (:require [mhuebert.clerk-cljs :refer [show-cljs]]
            [re-db.reactive :as r]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; The `re-db.xform` namespace contains tools for working with reactions/atoms as streams
;; which can be transformed using transducers.

(defonce !counter (r/atom 0))

(defonce !letters-a
  (xf/map (fn [value]
            (apply str (take value (repeat "A"))))
          !counter))

[@!counter @!letters-a]

(show-cljs
 [:div.p-3.bg-blue-600.text-white.rounded.font-sans.inline-block.cursor-pointer
  {:on-click #(render/clerk-eval '(reset! !counter (rand-int 50)))}
  "reset"])
