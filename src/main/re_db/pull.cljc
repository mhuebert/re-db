(ns re-db.pull
  (:require [re-db.entity :refer [entity #?(:cljs Entity)]]
            [re-db.util :as util]
            [re-db.patterns :as patterns :refer [*conn* *db*]]
            [re-db.protocols :as rp]
            [re-db.entity :as entity])
  #?(:clj (:import [re_db.entity Entity])))

(defn wrap-id [id many?] (if many? (mapv (fn [id] {:db/id id}) id)
                                   {:db/id id}))

(defn- pull*
  ([conn db e pullv] (pull* conn db e pullv #{}))
  ([conn db e pullv found]
   (let [e (patterns/resolve-e conn db e)]
     (reduce-kv
      (fn pull [m i pullexpr]
        (if (= '* pullexpr)
          (merge m (rp/eav db e))
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
                is-reverse (util/reverse-attr? a)
                forward-a (cond-> a is-reverse util/forward-attr)
                a-schema (rp/get-schema db forward-a)
                v (val-fn (entity/get* conn db e a false))
                is-ref (rp/ref? db a a-schema)
                is-many (or (rp/many? db a a-schema) is-reverse)
                v (cond (not is-ref) v

                        ;; ref without pull-expr
                        (nil? map-expr) (cond-> v
                                                is-ref
                                                (wrap-id is-many))

                        ;; recurse
                        (or (number? map-expr) (#{'... :...} map-expr))
                        (let [recursions (if (= 0 map-expr) false map-expr)
                              refs (when v
                                     (if recursions
                                       (let [found (conj found e)
                                             pullv (if (number? recursions)
                                                     ;; decrement recurse parameter
                                                     (update-in pullv [i forward-a] dec)
                                                     pullv)
                                             do-pull #(if (and (= :... recursions) (found %))
                                                        %
                                                        (pull* conn db % pullv found))]
                                         (if is-many
                                           (into [] (keep do-pull) v)
                                           (do-pull v)))
                                       (wrap-id v is-many)))]
                          refs #_(cond-> refs (not is-many) first))

                        ;; cardinality/many
                        is-many (mapv #(pull* conn db % map-expr) v)

                        ;; cardinality/one
                        :else (pull* conn db v map-expr))]
            (cond-> m (some? v) (assoc alias v)))))
      nil
      pullv))))

(defn pull
  "Returns entity as map, as well as linked entities specified in `pull`.

  (pull conn 1 [:children]) =>
    {:db/id 1
     :children [{:db/id 2}
                {:db/id 3}]}"
  ;; difference from clojure:
  ;; - if an attribute is not present, `nil` is provided
  ([pull-expr] (fn [e] (pull e pull-expr)))
  ([e pull-expr]
   (pull *conn* (rp/get-db *conn* *db*) e pull-expr))
  ([conn db e pull-expr]
   (pull* conn db e pull-expr)))