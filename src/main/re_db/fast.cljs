(ns re-db.fast
  (:require-macros re-db.fast))

(def nf-sentinel #js{})

(defn comp6
  "Comps functions which receive acc as 1st value followed by 4 unchanged values"
  [fs]
  (case (count fs)
    0 identity
    1 (first fs)
    2 (let [[f1 f2] fs]
        (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6))))
    3 (let [[f1 f2 f3] fs]
        (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6))))
    4 (let [[f1 f2 f3 f4] fs]
        (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6))))
    5 (let [[f1 f2 f3 f4 f5] fs]
        (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6))))
    6 (let [[f1 f2 f3 f4 f5 f6] fs]
        (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6))))
    (comp6 (cons (comp6 (take 6 fs))
                 (drop 6 fs)))))