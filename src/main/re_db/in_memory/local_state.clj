(ns re-db.in-memory.local-state
  (:require [clojure.string :as str]))

(defn code-location [form]
  (->> ((juxt :file :line :column) (meta form))
       (str/join ":")))

(defmacro local-state [owner & {:as opts}]
  `(~'re-db.in-memory.local-state/local-state* ~owner
    ~@(mapcat identity (assoc opts :location (code-location &form)))))
