(ns re-db.sync.entity-diff-2
  (:require [clojure.walk :as walk]
            [re-db.xform :as xf]
            [re-db.sync.transit :as t]))

(defn select-changed-keys [old-m new-m]
  (if (map? old-m)
    (->> (into (set (keys new-m))
               (keys old-m))
         (reduce (fn [m k]
                   (let [newv (new-m k)]
                     (cond-> m
                       (not= newv (old-m k))
                       (assoc k newv))))
                 nil))
    new-m))

(defn flatten-entities
  ([] {::entities {}})
  ([acc] acc)
  ([acc form]
   (let [!entities (volatile! (::entities acc))
         value (->> form (walk/postwalk
                          (fn [x]
                            (if-let [id (:db/id x)]
                              (do (when-let [diff (select-changed-keys (@!entities id) (dissoc x :db/id))]
                                    (vswap! !entities assoc id diff))
                                  (t/->Entity id))
                              x))))]
     {::entities @!entities
      :value value})))


(def result-handlers
  {::entities (fn [prev m] {:txs (reduce-kv (fn [out id v] (conj out (assoc v :db/id id))) [] m)})})

(defn txs
  "Returns transactions which diff successive values of ref"
  [ref]
  (xf/transform ref (xf/reducing-transducer flatten-entities)))

(comment
  (into []
        (xf/reducing-transducer flatten-entities)
        [{:db/id 1 :foo "bar"}
         {:db/id 1 :foo "bar" :glee "full"}])

  (into []
        (xf/reducing-transducer flatten-entities)
        [{:a {:b {:c {:db/id 1 :foo :bar}}}}
         {:a {:b {:c {:db/id 1 :foo :baz}}}}]))
