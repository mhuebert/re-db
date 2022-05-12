(ns re-db.pull
  (:require [re-db.entity :refer [entity #?(:cljs Entity)]]
            [re-db.util :as util]
            [re-db.protocols :as rp])
  #?(:clj (:import [re_db.entity Entity])))

(defn- pull*
  ([entity pullv] (pull* entity pullv #{}))
  ([^Entity the-entity pullv found]
   (when the-entity
     (reduce-kv
      (fn pull [m i pullexpr]
        (if (= '* pullexpr)
          (merge m @the-entity)
          (let [[a map-expr] (if (or (keyword? pullexpr) (list? pullexpr))
                               [pullexpr nil]
                               (first pullexpr))
                [a alias val-fn opts] (if (list? a)
                                        (let [{:as opts
                                               :keys [default limit]
                                               alias :as
                                               :or {alias (first a)}} (apply hash-map (rest a))
                                              a (first a)]
                                          (if (:db/id opts)
                                            [a :db/id #(vector a %) opts]
                                            [a alias (comp #(if limit (take limit %) %)
                                                           (or (when default #(util/some-or % default))
                                                               identity)) opts]))
                                        [a a identity])
                forward-a (cond-> a (util/reverse-attr? a) util/forward-attr)
                db (rp/get-db (.-conn the-entity) (.-db the-entity))
                a-schema (rp/get-schema db forward-a)
                v (val-fn (get the-entity a))]

            (assoc m alias
                     (cond (not (rp/ref? db a a-schema)) v

                           ;; ref without pull-expr
                           (nil? map-expr) v

                           ;; recurse
                           (or (number? map-expr) (= :... map-expr))
                           (let [recursions (if (= 0 map-expr) false map-expr)
                                 entities (when v
                                            (if recursions
                                              (let [found (conj found (.-e the-entity))
                                                    pullv (if (number? recursions)
                                                            ;; decrement recurse parameter
                                                            (update-in pullv [i forward-a] dec)
                                                            pullv)]
                                                (into #{}
                                                      (keep #(if (and (= :... recursions) (found %))
                                                               %
                                                               (some-> (entity db %)
                                                                       (pull* pullv found))))
                                                      v))
                                              v))]
                             (cond-> entities (not (rp/many? db a a-schema)) first))

                           ;; cardinality/many
                           (rp/many? db a a-schema) (into #{} (map #(pull* % map-expr)) v)

                           ;; cardinality/one
                           :else (pull* v map-expr))))))
      {}
      pullv))))

(defn ->entity [x] (if (instance? Entity x) x (entity x)))

(defn pull
  "Returns entity as map, as well as linked entities specified in `pull`.

  (pull conn 1 [:children]) =>
    {:db/id 1
     :children [{:db/id 2}
                {:db/id 3}]}"
  ;; difference from clojure:
  ;; - if an attribute is not present, `nil` is provided
  ([pull-expr] (fn [entity] (pull (->entity entity) pull-expr)))
  ([entity pull-expr]
   (pull* (->entity entity) pull-expr)))