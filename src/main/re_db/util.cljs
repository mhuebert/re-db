(ns re-db.util
  (:require [clojure.set :as set]))

;; from https://stackoverflow.com/a/14488425
(defn dissoc-in
  "Dis[join]s an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-some [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; modified from https://stackoverflow.com/a/14488425
(defn disj-in
  "Dis[join]s an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys] v]
  (if ks
    (if-some [nextmap (get m k)]
      (let [newmap (disj-in nextmap ks v)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (let [set (disj (get m k) v)]
      (if (seq set)
        (assoc m k set)
        (dissoc m k)))))

(defn update-set
  "updates set in a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys] f]
  (if ks
    (if-some [nextmap (get m k)]
      (let [newmap (update-set nextmap ks f)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (let [set (f (get m k))]
      (if (seq set)
        (assoc m k set)
        (dissoc m k)))))

;; modified from https://stackoverflow.com/a/14488425
(defn difference-in [m [k & ks] removals]
  (if ks
    (if-some [nextmap (get m k)]
      (let [newmap (difference-in nextmap ks removals)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (let [set (set/difference (get m k) removals)]
      (if (seq set)
        (assoc m k set)
        (dissoc m k)))))

 (defn guard [x f] (when (f x) x))