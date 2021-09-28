(ns re-db.util)

(defn guard [x f] (when (f x) x))

(defn set-replace [s old new] (-> s (disj old) (conj new)))