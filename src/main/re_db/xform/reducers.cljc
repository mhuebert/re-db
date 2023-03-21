(ns re-db.xform.reducers
  (:refer-clojure :exclude [frequencies group-by]))

(defn before:after
  ([] [])
  ([acc] acc)
  ([acc x] (conj (empty acc) (second acc) x)))

(defn frequencies []
  (fn
    ([] {})
    ([acc] acc)
    ([acc x] (update acc x (fnil inc 0)))))

(defn group-by [key-fn]
  (fn
    ([] {})
    ([acc] acc)
    ([acc x] (update acc (key-fn x) (fnil conj []) x))))

(defn sliding-window [size]
  (fn
    ([] ())
    ([acc] acc)
    ([acc x] (cons x (take (dec size) acc)))))