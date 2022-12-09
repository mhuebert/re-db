(ns re-db.sync.server
  (:require [re-db.reactive :as r]
            [re-db.subscriptions :as s]
            [re-db.sync.transit :refer [entity-pointer]]
            [re-db.xform :as xf]))

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

(defn send-error [client-id id send-fn error]
  (send-fn client-id [:re-db.sync/tx
                      [[:db/add {:re-db/query-result id} :result {:error error}]]]))

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

;; atom which can act as source for an event-stream
(defonce !last-event (atom nil))

(defn watch-ref
  "Adds watch for ref, syncing changes with client."
  [client-id id ref send-fn]
  (reset! !last-event {:event :watch-ref :client-id client-id :ref-id id})
  (let [!tx-ref ($diff-tx id ref)]
    (swap! !watches update client-id (fnil conj #{})
           (r/update-meta! !tx-ref merge {::id id}))
    (add-watch !tx-ref client-id (fn [_ _ _ txs]
                                   (reset! !last-event {:event :send
                                                        :client-id client-id
                                                        :ref-id id})
                                   (send-fn client-id [:re-db.sync/tx txs])))
    (prn :will-send send-fn [:re-db.sync/tx (diff-tx id [nil @ref])])
    ;; initial sync of current value
    (send-fn client-id [:re-db.sync/tx (diff-tx id [nil @ref])])))

(defn unwatch-ref
  "Removes watch for ref."
  [client-id id ref]
  (reset! !last-event {:event :unwatch-ref :client-id client-id :ref-id id})
  (let [!tx-ref ($diff-tx id ref)]
    (swap! !watches update client-id disj !tx-ref)
    (remove-watch !tx-ref client-id)))

(defn unwatch-all [client-id]
  (reset! !last-event {:event :unwatch-all :client-id client-id})
  (doseq [ref (@!watches client-id)]
    (remove-watch ref client-id))
  (swap! !watches dissoc client-id)
  nil)