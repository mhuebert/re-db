(ns re-db.util)

(defn guard [x f] (when (f x) x))