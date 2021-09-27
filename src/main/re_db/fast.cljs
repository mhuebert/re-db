(ns re-db.fast
  (:require-macros [re-db.fast :as fast]))

(def nf-sentinel #js{})

(defn comp6
  "Comps functions which receive acc as 1st value followed by 6 unchanged values"
  [fs]
  (let [[f1 f2 f3 f4 f5 f6 f7 f8] fs]
    (case (count fs)
      0 (fn [acc _ _ _ _ _ _] acc)
      1 f1
      2 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6)))
      3 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6)))
      4 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6)))
      5 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6)))
      6 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6)))
      7 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6) (f7 a1 a2 a3 a4 a5 a6)))
      8 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6) (f7 a1 a2 a3 a4 a5 a6) (f8 a1 a2 a3 a4 a5 a6)))
      (comp6 (cons (comp6 (take 8 fs))
                   (drop 8 fs))))))

(defn merge-maps [m1 m2]
  (if (some? m1)
    (merge m1 m2)
    m2)
  (merge m1 m2))

(defn assoc-seq! [m a v]
  (if (seq v)
    (assoc! m a v)
    (dissoc! m a)))

(defn assoc-seq [m a v]
  (if (seq v)
    (assoc m a v)
    (dissoc m a)))


(defn assoc-some! [m a v]
  (if (some? v)
    (assoc! m a v)
    (dissoc! m a)))

(defn assoc-some [m a v]
  (if (some? v)
    (assoc m a v)
    (dissoc m a)))