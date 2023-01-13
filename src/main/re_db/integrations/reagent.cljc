(ns re-db.integrations.reagent
  (:require [reagent.ratom :as ratom]
            [re-db.reactive :as r]))

#?(:cljs
   (extend-type ratom/Reaction
     r/ICountReferences
     (r/dispose! [this] (ratom/dispose! this))
     (r/add-on-dispose! [this f] (ratom/add-on-dispose! this f))
     (r/get-watches [^ratom/Reaction this] (.-watches this))
     (r/set-watches! [^ratom/Reaction this watches] (set! (.-watches this) watches))
     (r/detached? [^ratom/Reaction this] (.-detached? this))
     (r/detach! [^ratom/Reaction this]
       (set! (.-detached? this) true)
      @this
      this)))

#?(:cljs
   (extend-type r/Reaction
     ratom/IDisposable
     (dispose! [this] (r/dispose! this))
     (add-on-dispose! [this f] (r/add-on-dispose! this f))))

#?(:cljs
   (set! r/get-reagent-context (fn [] ratom/*ratom-context*)))

#?(:cljs
   (set! r/reagent-notify-deref-watcher! ratom/notify-deref-watcher!))