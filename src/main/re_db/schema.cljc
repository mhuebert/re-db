(ns re-db.schema
  (:refer-clojure :exclude [ref long boolean]))

;; schema
(def one {:db/cardinality :db.cardinality/one})
(def many {:db/cardinality :db.cardinality/many})
(def unique-id {:db/unique :db.unique/identity})
(def unique-value {:db/unique :db.unique/value})
(def ave {:db/index true})
(def ae {:db/index-ae true})

;; types
(def string {:db/valueType :db.type/string})
(def ref {:db/valueType :db.type/ref})
(def long {:db/valueType :db.type/long})
(def boolean {:db/valueType :db.type/boolean})