(ns re-db.integrations.reagent
  (:require [reagent.ratom :as ratom]
            [re-db.reactive :as r]))

(extend-type ratom/Reaction
  r/IDispose
  (r/get-dispose-fns [^ratom/Reaction this]
    (vec (.-on-dispose-arr this)))
  (r/set-dispose-fns! [^ratom/Reaction this new-fns]
    (set! (.-on-dispose-arr this) (to-array new-fns))))

(extend-type r/Reaction
  ratom/IDisposable
  (dispose! [this] (r/dispose! this))
  (add-on-dispose! [this f] (r/add-on-dispose! this f)))

(set! r/get-reagent-context (fn [] ratom/*ratom-context*))
(set! r/reagent-notify-deref-watcher! ratom/notify-deref-watcher!)