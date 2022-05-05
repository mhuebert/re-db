(ns re-db.reagent.local-state
  (:require [clojure.string :as str]))

(defn code-location [form]
  (->> ((juxt :file :line :column) (meta form))
       (str/join ":")))

(defmacro local-state [owner & {:as opts}]
  `(~'re-db.reagent.local-state/local-state* ~owner
    ~@(mapcat identity (assoc opts :location (code-location &form)))))
