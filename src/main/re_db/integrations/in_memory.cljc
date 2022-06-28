(ns re-db.integrations.in-memory
  (:require [re-db.protocols :as rp]
            [re-db.fast :as fast]
            [re-db.in-memory :as mem]))

(extend-type #?(:cljs default :clj java.lang.Object)
  rp/ITriple
  (eav
    ([db e] (some-> (fast/gets db :eav e) (assoc :db/id e)))
    ([db e a] (fast/gets db :eav e a)))
  (ave [db a v]
    (let [a-schema (mem/get-schema db a)]
      (if (mem/ave? a-schema)
        (fast/gets db :ave a v)
        ;; TODO - add index to conn?
        (let [pred (if (mem/many? a-schema) contains? =)]
          (->> (:eav db)
               (reduce-kv
                (fn [out e m]
                  (cond-> out
                          (pred (m a) v)
                          (conj e)))
                #{}))))))
  (ae [db a]
    (if (mem/ae? (mem/get-schema db a))
      (fast/gets db :ae a)
      (do
        ;; TODO - add index to conn?
        (reduce-kv (fn [out e ent] (if (contains? ent a)
                                     (conj out e)
                                     out)) [] (:eav db)))))
  (datom-a [db a] a)
  (get-schema [db a] (mem/get-schema db a))
  (id->ident [db e] e)
  (ref?
    ([db a] (mem/ref? (mem/get-schema db a)))
    ([db a schema] (mem/ref? schema)))
  (unique?
    ([db a] (mem/unique? (mem/get-schema db a)))
    ([db a schema] (mem/unique? schema)))
  (many?
    ([db a] (mem/many? (mem/get-schema db a)))
    ([db a schema] (mem/many? schema)))
  (-transact
    ([db conn txs] (mem/transact! conn txs))
    ([db conn txs opts] (mem/transact! conn txs opts)))
  (-merge-schema [db conn schema] (mem/merge-schema! conn schema))
  (doto-report-triples [db f report]
    (let [many? (memoize (fn [a] (mem/many? (mem/get-schema (:db-after report) a))))]
      (doseq [[e a v pv] (:datoms report)]
        (if (many? a)
          (do (doseq [v v] (f e a v))
              (doseq [pv pv] (f e a pv)))
          (do (when (some? v) (f e a v))
              (when (some? pv) (f e a pv))))))))