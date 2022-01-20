(ns re-db.notebooks.datomic
  (:require [datomic.api :as d]))

(def db-uri "datomic:mem://foo")

(def conn (d/connect (doto db-uri d/create-database)))

(defn schema-ident [[attr type cardinality & {:as params}]]
  (merge {:db/ident attr
          :db/valueType (keyword "db.type" (name type))
          :db/cardinality (keyword "db.cardinality" (name cardinality))}
         params))

@(d/transact conn (map schema-ident [[:movie/title :string :one]
                                     [:movie/genre :string :one]
                                     [:movie/release-year :long :one]]))

@(d/transact conn
             [#:movie{:title "The Goonies"
                      :movie/genre "action/adventure"
                      :movie/release-year 1985}
              #:movie{:title "Commando"
                      :movie/genre "thriller/action"
                      :movie/release-year 1985}
              #:movie{:title "Repo Man"
                      :genre "punk dystopia"
                      :release-year 1984}])

(d/q '[:find ?movie-title
       :where [_ :movie/title ?movie-title]]
     (d/db conn))

#{1 2 3}
