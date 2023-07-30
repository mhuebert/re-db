(ns re-db.integrations.in-memory
  (:require [re-db.triplestore :as ts]
            [re-db.fast :as fast]
            [re-db.in-memory :as mem]))

(extend-type #?(:cljs default :clj java.lang.Object)
  ts/ITripleStore
  (eav
    ([db e] (when-let [e (mem/resolve-e db e)]
              (some-> (fast/gets db :eav e)
                      (assoc :db/id e))))
    ([db a-schema e a]
     (when-let [e (mem/resolve-e db e)]
       (fast/gets db :eav e a))))
  (ave [db a-schema a v]
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
              #{})))))
  (ae [db a-schema a]
    (fast/gets db :ae a)
    (do
      ;; TODO - add index to conn?
      (reduce-kv (fn [out e ent] (if (contains? ent a)
                                   (conj out e)
                                   out)) [] (:eav db))))
  (datom-a [db a] a)
  (-get-schema [db a] (mem/get-schema db a))
  (id->ident [db e] (or #_(:db/ident (mem/get-entity db e))
                     ;; not enabled here because in-memory doesn't
                     ;; replace idents with entity ids in datoms
                     e))
  (ref? [db schema] (mem/ref? schema))
  (unique? [db schema] (mem/unique? schema))
  (many? [db schema] (mem/many? schema))
  (component? [db schema] (mem/component? schema))
  (-transact
    ([db conn txs] (mem/transact! conn txs))
    ([db conn txs opts] (mem/transact! conn txs opts)))
  (-merge-schema [db conn schema] (mem/merge-schema! conn schema))
  (report-triples [db report f]
    (let [many? (memoize (fn [a] (mem/many? (mem/get-schema (:db-after report) a))))]
      (doseq [[e a v pv] (:datoms report)]
        (if (many? a)
          (do (doseq [v v] (f e a v))
              (doseq [pv pv] (f e a pv)))
          (do (when (some? v) (f e a v))
              (when (some? pv) (f e a pv))))))))