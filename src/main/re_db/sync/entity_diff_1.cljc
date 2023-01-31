(ns re-db.sync.entity-diff-1
  (:require [re-db.memo :as memo]
            [re-db.sync :as-alias sync]
            [re-db.sync.transit :refer [entity-pointer]]
            [re-db.xform :as xf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity diffing - transacting only changes when a query updates
;;
;; This is a quick/naive implementation

(defn diff-entity [old-m new-m]
  (if (map? old-m)
    (let [ks (-> (keys new-m)
                 set
                 (into (keys old-m))
                 (disj :db/id))
          diff (reduce (fn [m k]
                         (let [v (new-m k)
                               pv (old-m k)]
                           (cond-> m
                                   (not= v pv)
                                   (assoc k v))))
                       nil
                       ks)]
      (when diff
        (assoc diff :db/id (:db/id new-m))))
    new-m))

(defn diff-entities [old-entities new-entities]
  (if (and (sequential? old-entities) (not-empty old-entities))
    (let [o (reduce #(assoc %1 (:db/id %2) %2) {} old-entities)]
      (->> new-entities
           (reduce (fn [out new-m]
                     (if-let [m (diff-entity (o (:db/id new-m)) new-m)]
                       (conj out m)
                       out)) [])))
    new-entities))

(defn diff
  ([[old new]] (diff old new))
  ([old new]
   (if (sequential? new)
     (if (:db/id (first new)) (diff-entities old new) new)
     (if (:db/id new) (diff-entity old new) new))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result transactions - for sending re-db transaction format over the wire

(defn diff-tx
  "Replaces entities in result (detected via presence of :db/id) with instances of ->Entity,
   returns new result and list of transactions containing entity data.

   Recognizes only a single entity-map, or a list of entity-maps. Any other shape
   will be shipped as-is."
  [[old-result new-value]]
  (let [{old-value :value} old-result
        shape (cond (:db/id new-value) :entities/one
                    (and (sequential? new-value)
                         (:db/id (first new-value))) :entities/many
                    :else :value)]
    (assoc (case shape
             :value {:value new-value}
             :entities/one {:value (entity-pointer new-value)
                            :txs [(diff-entity old-value new-value)]}
             :entities/many {:value (mapv entity-pointer new-value)
                             :txs (diff-entities old-value new-value)})
      ::sync/init (case shape
                    :value {:value new-value}
                    :entities/one {:value (entity-pointer new-value)
                                   :txs [new-value]}
                    :entities/many {:value (mapv entity-pointer new-value)
                                    :txs new-value}))))

(defn send-error [client-id id send-fn error]
  (send-fn client-id [:sync/tx
                      [[:db/add {:sync/watch-result id} :result {:error error}]]]))

;; currently only used for tests
(memo/defn-memo $diff
  [ref]
  (xf/transform ref
    (xf/before:after)
    (map diff)))

(memo/defn-memo $diff-tx
  "transactions of diffs of successive values of ref"
  [ref]
  (xf/transform ref
    (xf/before:after)
    (map diff-tx)))