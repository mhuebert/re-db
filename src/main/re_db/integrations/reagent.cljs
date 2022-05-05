(ns re-db.integrations.reagent
  (:require [reagent.ratom :as ratom]
            [re-db.reactive :as r]))

(extend-type ratom/Reaction
  r/IDispose
  (r/get-dispose-fns [^js this]
    (vec (.-on-dispose-arr this)))
  (r/set-dispose-fns! [^js this new-fns]
    (set! (.-on-dispose-arr this) (to-array new-fns))))

(extend-type r/Reaction
  ratom/IDisposable
  (dispose! [this] (r/dispose! this))
  (add-on-dispose! [this f] (r/add-on-dispose! this f)))

