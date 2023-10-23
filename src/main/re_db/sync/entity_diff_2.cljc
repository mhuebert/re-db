(ns re-db.sync.entity-diff-2
  (:require [clojure.walk :as walk]
            [re-db.sync :as-alias sync]
            [re-db.sync.transit :as t]
            [re-db.xform :as xf]))

;; sync any data structure containing entities (identifiable by id-key).
;; flatten the result into a stream of {:value, ::entities} maps.

(defn select-changed-keys [old-m new-m]
  ;; If a new key differs from an old key, update it.
  ;; don't assume a missing key implies a nil value - entities can show up in multiple places
  ;; with different keys.
  (if (map? old-m)
    (->> new-m
         (reduce-kv (fn [m k newv]
                      (if (not= newv (old-m k))
                        (assoc m k newv)
                        m))
                    nil))
    new-m))

(defn flatten-entities [acc form]
  (let [{prev-value :value
         [id-key prev-entities] ::entities} (::sync/init acc)
        !current-entities (volatile! {})
        !entity-changes (volatile! {})
        next-value (->> form (walk/postwalk
                              (fn [x]
                                (if-let [id (id-key x)]
                                  (let [m (dissoc x id-key)]
                                    (vswap! !current-entities update id merge m)
                                    (when-let [diff (select-changed-keys (prev-entities id) m)]
                                      (vswap! !entity-changes update id merge diff))
                                    (t/->Entity id-key id))
                                  x))))
        next-entities @!entity-changes]
    (cond-> {::sync/init {::entities [id-key @!current-entities]
                          :value next-value}}
      (seq next-entities)
      (assoc ::entities next-entities)
      (not= next-value prev-value)
      (assoc :value next-value))))

(defn result-handlers []
  {::entities (fn [prev [id-key m]]
                {:txs (reduce-kv (fn [out id v] (conj out (assoc v :db/id [id-key id]))) [] m)})})

(defn transducer [id-key]
  (xf/reducing-transducer flatten-entities
                          {::sync/init {::entities [id-key {}]
                                        :value nil}}
                          #(dissoc % ::sync/init)))

(defn $txs
  "Returns transactions which diff successive values of ref"
  [id-key ref]
  (xf/transform ref (transducer id-key)))

(comment
  (into []
        (transducer :db/id)
        [{:db/id 1 :foo "bar"}
         {:db/id 1 :foo "baz"}
         {:db/id 1 :foo "baz"}
         {:db/id 1 :foo "bar"}
         {:db/id 2 :foo "bar"}])

  (into []
        (transducer :db/id)
        [{:a {:b {:c {:db/id 1 :foo :bar}}}}
         {:a {:b {:c {:db/id 1 :foo :baz}}}}
         {:a {:b {:c {:db/id 1 :foo :baz}}}}]))
