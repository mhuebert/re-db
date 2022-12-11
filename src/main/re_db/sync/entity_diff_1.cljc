(ns re-db.sync.entity-diff-1
  (:require [re-db.subscriptions :as s]
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

   Currently recognizes only a single entity-map, or a list of entity-maps. Any other shape
   will be shipped as-is."
  [id [old-result new-result]]
  (let [db-id {:re-db.sync/watch-result id}
        {new-value :value new-error :error} new-result
        shape (cond new-error :error
                    (:db/id new-value) :entities/one
                    (and (sequential? new-value)
                         (:db/id (first new-value))) :entities/many
                    :else :value)
        init-tx [[:db/add db-id :result new-result]]]
    (with-meta (case shape
                 (:value :error) init-tx
                 :entities/one [(diff-entity (:value old-result) (:value new-result))
                                [:db/add db-id :result (update new-result :value (comp entity-pointer :db/id))]]
                 :entities/many (conj (diff-entities (:value old-result) (:value new-result))
                                      [:db/add db-id :result (update new-result :value #(mapv (comp entity-pointer :db/id) %))]))
               {::sync/initial-tx init-tx})))

(defn send-error [client-id id send-fn error]
  (send-fn client-id [:re-db.sync/tx
                      [[:db/add {:re-db.sync/watch-result id} :result {:error error}]]]))

;; currently only used for tests
(s/def $diff
  (fn [ref]
    (xf/transform ref
                  (xf/before:after)
                  (map diff))))

(s/def $diff-tx
  ;; transactions of diffs of successive values of ref
  (fn [id ref]
    (xf/transform ref
                  (xf/before:after)
                  (map #(diff-tx id %)))))