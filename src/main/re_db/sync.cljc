(ns re-db.sync
  (:require [re-db.subscriptions :as subs]
            [re-db.xform :as xf]))

(subs/def $values
  (fn [!ref] (xf/map (fn [v] [::value v]) !ref)))