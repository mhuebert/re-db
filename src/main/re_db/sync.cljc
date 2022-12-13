(ns re-db.sync
  (:require [re-db.subscriptions :as subs]
            [re-db.xform :as xf]))

(subs/def $snapshots
  (fn [qvec !ref] (xf/map (fn [v] [::snapshot qvec v]) !ref)))