(ns re-db.schema
  (:refer-clojure :exclude [bigdec bigint boolean bytes double float keyword long ref symbol uuid]))

;; schema
(def one {:db/cardinality :db.cardinality/one})
(def many {:db/cardinality :db.cardinality/many})
(def unique-id {:db/unique :db.unique/identity})
(def unique-value {:db/unique :db.unique/value})
(def component {:db/isComponent true})
(def fulltext {:db/fulltext true})
(def ave {:db/index true})
(def ae {:db/index-ae true})


;; types
(def bigdec {:db/valueType :db.type/bigdec})
(def bigint {:db/valueType :db.type/bigint})
(def boolean {:db/valueType :db.type/boolean})
(def bytes {:db/valueType :db.type/bytes})
(def double {:db/valueType :db.type/double})
(def float {:db/valueType :db.type/float})
(def instant {:db/valueType :db.type/instant})
(def keyword {:db/valueType :db.type/keyword})
(def long {:db/valueType :db.type/long})
(def ref {:db/valueType :db.type/ref})
(def string {:db/valueType :db.type/string})
(def symbol {:db/valueType :db.type/symbol})
(def uri {:db/valueType :db.type/uri})
(def uuid {:db/valueType :db.type/uuid})

(defn tuple [& {:keys [attrs type types]}]
  (merge {:db/valueType :db.type/typle}
         (cond attrs {:db/tupleAttrs attrs}
               type {:db/tupleType type}
               types {:db/tupleTypes types})))