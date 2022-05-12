(ns re-db.sync.server
  (:require [cognitect.transit :as transit]
            [re-db.xform :as xf]
            [re-db.subscriptions :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity references over the wire

(defonce _ (deftype TaggedValue [tag rep]))

(def write-handlers
  {TaggedValue (transit/write-handler #(.-tag %) #(.-rep %))})

(defn entity-pointer [id] (TaggedValue. "re-db/entity" id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity diffing - transacting only changes when a query updates

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
  (let [db-id {:re-db/query-result id}
        {:keys [value error]} new-result
        shape (cond error :error
                    (sequential? value) (if (:db/id (first value))
                                          :entities/many
                                          :value)
                    :else (if (:db/id value)
                            :entities/one
                            :value))]
    (case shape
      :error [[:db/add db-id :result {:error error}]]
      :value [[:db/add db-id :result {:value value}]]
      :entities/one [(diff-entity (:value old-result) value)
                     [:db/add db-id :result {:value (entity-pointer (:db/id value))}]]
      :entities/many (conj (diff-entities (:value old-result) value)
                           [:db/add db-id :result {:value (mapv (comp entity-pointer :db/id) value)}]))))


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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client subscriptions - management of stateful websocket sessions

(defonce !watches (atom {})) ;; a map of {<client-id> #{...refs}}

(defn watch-ref
  "Adds watch for ref, syncing changes with client."
  [client-id id ref send-fn]
  (let [!tx-ref ($diff-tx id ref)]
    (swap! !watches update client-id (fnil conj #{}) !tx-ref)
    (add-watch !tx-ref client-id (fn [_ _ _ txs]
                                   (send-fn client-id [:re-db/sync-tx txs])))
    ;; initial sync of current value
    (send-fn client-id [:re-db/sync-tx (diff-tx id [nil @ref])])))

(defn unwatch-ref
  "Removes watch for ref."
  [client-id id ref]
  (let [!tx-ref ($diff-tx id ref)]
    (swap! !watches update client-id disj !tx-ref)
    (remove-watch !tx-ref client-id)))

(defn unwatch-all [client-id]
  (doseq [ref (@!watches client-id)]
    (remove-watch ref client-id))
  (swap! !watches dissoc client-id)
  nil)