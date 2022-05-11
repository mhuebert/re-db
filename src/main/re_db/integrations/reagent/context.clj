(ns re-db.integrations.reagent.context
  (:require [re-db.patterns :as patterns]))

(defmacro bind-conn [conn body]
  `(let [conn# ~conn]
     (~'re-db.api/with-conn conn#
      [~'re-db.integrations.reagent.context/bind-conn conn# ~body])))