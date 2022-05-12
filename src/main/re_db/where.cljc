(ns re-db.where
  (:require [re-db.protocols :as rp]
            [re-db.patterns :as patterns :refer [current-db *conn*]]
            [re-db.entity :refer [entity]]))

(defn clause-filter [conn db clause]
  (if (or (fn? clause) (keyword? clause))
    (fn [entity] (clause entity))
    (let [[a v] clause]
      (cond
        ;; arbitrary function
        (fn? v) (fn [entity] (v (get entity a)))
        ;; refs - resolve `v` lookup-refs & compare to :db/id of the thing
        (rp/ref? (current-db) a) (fn [entity] (= (patterns/resolve-v conn db a v) (:db/id (get entity a))))
        :else (fn [entity] (= (get entity a) v))))))

(defn where
  ([clauses] (where *conn* (current-db) clauses))
  ([conn db clauses]
   ;; first clause reads from db
   (let [[clause & clauses] clauses
         [a v] (if (keyword? clause) [clause nil] clause)]
     (->> (cond (nil? v) (patterns/ae conn db a)
                (fn? v) (filter v (patterns/ae conn db a))
                :else (patterns/ave conn db a v))
          (into #{}
                (comp (map entity)
                      ;; additional clauses filter entities from step 1
                      (filter (if (seq clauses)
                                (apply every-pred (map #(clause-filter conn db %) clauses))
                                identity))))))))