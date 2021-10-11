(ns re-db.schema)

;; schema
(def many {:db/cardinality :db.cardinality/many})
(def ref {:db/valueType :db.type/ref})
(def unique-id {:db/unique :db.unique/identity})
(def unique-value {:db/unique :db.unique/value})
(def ave {:db/index true})
(def ae {:db/index-ae true})