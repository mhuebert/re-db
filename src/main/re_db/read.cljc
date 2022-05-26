(ns re-db.read
  (:require [re-db.reactive :as r]
            [re-db.fast :as fast]
            [re-db.util :as u]
            [re-db.protocols :as rp]
            [re-db.in-memory :as mem])
  #?(:cljs (:require-macros [re-db.read :refer [resolve-entity-e!]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Listeners
;;
;; A reactive signal per [e a v] pattern which invalidates listeners

(defonce !listeners (atom {}))

(defn clear-listeners! [] (swap! !listeners empty))

(defn make-listener [conn e a v]
  (r/->RAtom nil
             {}
             [(fn [_] (swap! !listeners u/dissoc-in [conn e a v]))]
             {:pattern [e a v]}))


(defn handle-report!
  "Invalidate readers for a tx-report from conn based on datoms transacted"
  [conn tx-report]
  (when tx-report
    (if-let [listeners (@!listeners conn)]
      (let [result (volatile! #{})
            collect! #(some->> % (vswap! result conj))
            _ (rp/doto-report-triples
               (rp/db conn)
               (fn [e a v]
                 ;; triggers patterns for datom, with "early termination"
                 ;; for paths that don't lead to any invalidators.
                 (when-some [e (listeners e)]
                   (collect! (fast/gets-some e nil nil)) ;; e__
                   (collect! (fast/gets-some e a nil))) ;; ea_
                 (when-some [_ (listeners nil)]
                   (when-some [__ (_ nil)]
                     (collect! (__ v))) ;; __v (used for refs)
                   (when-some [_a (_ a)]
                     (collect! (_a v)) ;; _av
                     (collect! (_a nil))))) ;; _a_
               tx-report)
            invalidated @result]
        #_(prn :invalidating (mapv (comp :pattern meta) invalidated))
        (doseq [listener invalidated] (r/invalidate! listener))
        (assoc tx-report ::handled (count invalidated)))
      tx-report)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic variables for the current database connection & value

;; reads are tracked per connection (a reference type, the value changes with each transaction)
(defonce ^:dynamic *conn* (mem/create-conn))

;; the point-in-time value of the conn (db) can optionally be overridden
(defonce ^:dynamic *db* nil)

(defn current-conn [] *conn*)

(defn current-db
  ([] (current-db *conn* *db*))
  ([conn] (current-db conn *db*))
  ([conn db] (or db (rp/db conn))))

(defmacro with-conn
  [conn & body]
  `(binding [*conn* (mem/->conn ~conn)] (assert *conn*) ~@body))

(defmacro with-db
  "Evaluates body with `db` bound, but without tracking dependencies"
  [db & body]
  `(binding [*db* ~db] ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read tracking

(declare resolve-pattern)

(defn depend-on-triple!
  ([e a v] (depend-on-triple! *conn* e a v))
  ([conn e a v]
   (when (and conn (r/owner))
     (let [[e a v :as triple] (resolve-pattern conn (rp/db conn) e a v)]
       @(or (fast/gets-some @!listeners conn e a v)
            (doto (make-listener conn e a v)
              (->> (swap! !listeners assoc-in [conn e a v]))))
       triple))))

(defn ae
  ([a] (ae *conn* (current-db *conn*) a))
  ([conn db a]
   (some-> conn (depend-on-triple! nil a nil))
   (rp/ae db a)))

(defn resolve-lookup-ref
  ([e] (resolve-lookup-ref *conn* (current-db *conn*) e))
  ([conn db [a v :as e]]
   (assert (rp/unique? db a) "Lookup ref attribute must be unique")
   (if (vector? v) ;; nested lookup ref
     (resolve-lookup-ref conn db [a (resolve-lookup-ref conn db v)])
     (when v
       (depend-on-triple! conn nil a v)
       (first (rp/ave db a v))))))

(defn resolve-e
  ([e] (resolve-e *conn* (current-db *conn*) e))
  ([conn db e]
   (if (vector? e)
     (resolve-lookup-ref conn db e)
     (:db/id e e))))

(defn resolve-v
  ([a v] (resolve-v *conn* (current-db *conn*) a v))
  ([conn db a v]
   (cond->> v
            (and v (rp/ref? db a))
            (resolve-e conn db))))

(defn eav
  ([e] (eav *conn* (current-db *conn*) e))
  ([e a] (eav *conn* (current-db *conn*) e a))
  ([conn db e]
   (when-let [id (resolve-e conn db e)]
     (depend-on-triple! conn id nil nil)
     (rp/eav db id)))
  ([conn db e a]
   (some-> conn (depend-on-triple! e a nil))
   (rp/eav db e a)))

(defn ave
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  ([a v] (ave *conn* (current-db *conn*) a v))
  ([conn db a v]
   (when-let [v (resolve-v conn db a v)]
     (some-> conn (depend-on-triple! nil a v))
     (rp/ave db a v))))

(defn resolve-pattern
  ([e a v] (resolve-pattern *conn* (current-db *conn*) e a v))
  ([conn db e a v]
   [(some->> e (resolve-e conn db))
    (some->> a (rp/datom-a db))
    (some->> v (resolve-v conn db a))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity API
;;

(defonce ^:dynamic *attribute-resolvers* {})

(defmacro resolve-entity-e! [conn db e-sym e-resolved-sym]
  `(do (when-not ~e-resolved-sym
         (when-some [e# (resolve-e ~conn ~db ~e-sym)]
           (set! ~e-sym e#)
           (set! ~e-resolved-sym true)))
       ~e-sym))

(declare entity)

(defn get* [conn db e a wrap-ref]
  (if (= :db/id a)
    e
    (if-let [resolver (*attribute-resolvers* a)]
      (resolver (entity conn db e))
      (let [is-reverse (u/reverse-attr? a)
            a (cond-> a is-reverse u/forward-attr)
            a-schema (rp/get-schema db a)
            is-ref (rp/ref? db a a-schema)
            v (if is-reverse
                (rp/ave db a e)
                (rp/eav db e a))]

        (if is-reverse
          (depend-on-triple! nil a e) ;; [_ a v]
          (depend-on-triple! e a nil)) ;; [e a _]

        (if (and is-ref wrap-ref)
          (if (or is-reverse
                  (rp/many? db a a-schema))
            (mapv #(wrap-ref conn db %) v)
            (wrap-ref conn db v))
          v)))))

(defprotocol IEntity
  (conn [entity])
  (db [entity]))

(u/support-clj-protocols
  (deftype Entity [conn db ^:volatile-mutable e ^:volatile-mutable e-resolved? meta]
    IEntity
    (conn [this] conn)
    (db [this] db)
    IMeta
    (-meta [this] meta)
    IWithMeta
    (-with-meta [this new-meta]
      (if (identical? new-meta meta)
        this
        (Entity. conn db e e-resolved? new-meta)))
    IHash
    (-hash [this]
      (let [db (current-db conn db)]
        (resolve-entity-e! conn db e e-resolved?)
        (hash [e (rp/eav db e)])))

    IEquiv
    (-equiv [this other]
      (and (instance? Entity other)
           (identical? conn (.-conn ^Entity other))
           (= (hash this) (hash other))
           (= (:db/id this) (:db/id other))))
    ILookup
    (-lookup [o a]
      (let [db (current-db conn db)]
        (resolve-entity-e! conn db e e-resolved?)
        (get* conn db e a entity)))
    (-lookup [o a nf]
      (case nf
        ::unwrapped
        (let [db (current-db conn db)]
          (resolve-entity-e! conn db e e-resolved?)
          (get* conn db e a false))
        (if-some [v (get o a)] v nf)))
    IDeref
    (-deref [this]
      (let [db (current-db conn db)]
        (when-let [e (resolve-entity-e! conn db e e-resolved?)]
          (depend-on-triple! e nil nil)
          (rp/eav db e))))
    ISeqable
    (-seq [this] (seq @this))))

(defn entity
  ([conn db e]
   (let [e (:db/id e e)
         e (or (resolve-e conn (current-db conn) e) e)]
     (->Entity conn db e false nil)))
  ([conn e] (entity conn nil e))
  ([e] (entity *conn* nil e)))

;; difference from others: no isComponent
(defn touch [entity] @entity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pull API
;;

(defn wrap-v [v conn db many? f]
  (if many? (mapv (fn [v] (f conn db v)) v)
            (f conn db v)))

(defn- pull*
  ([conn db e pullv wrap-ref] (pull* conn db e pullv #{} wrap-ref))
  ([conn db e pullv found wrap-ref]
   (let [e (resolve-e conn db e)]
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
                                                           (or (when default #(u/some-or % default))
                                                               identity)) opts]))
                                        [a a identity])
                is-reverse (u/reverse-attr? a)
                forward-a (cond-> a is-reverse u/forward-attr)
                a-schema (rp/get-schema db forward-a)
                v (val-fn (get* conn db e a false))
                is-ref (rp/ref? db a a-schema)
                is-many (or (rp/many? db a a-schema) is-reverse)
                v (cond (not is-ref) v

                        ;; ref without pull-expr
                        (nil? map-expr) (cond-> v
                                                is-ref
                                                (wrap-v conn db is-many wrap-ref))

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
                                                        (pull* conn db % pullv found wrap-ref))]
                                         (if is-many
                                           (into [] (keep do-pull) v)
                                           (do-pull v)))
                                       (wrap-v v conn db is-many wrap-ref)))]
                          refs #_(cond-> refs (not is-many) first))

                        ;; cardinality/many
                        is-many (mapv #(pull* conn db % map-expr wrap-ref) v)

                        ;; cardinality/one
                        :else (pull* conn db v map-expr wrap-ref))]
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
   (pull e pull-expr (fn [conn db e] {:db/id e})))
  ([e pull-expr wrap-ref]
   (pull* *conn* (current-db) e pull-expr wrap-ref)))

(defn pull-entities
  ([pull-expr] (fn [e] (pull-entities e pull-expr)))
  ([e pull-expr] (pull* *conn* (current-db) e pull-expr entity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Where
;;

(defn clause-filter [conn db clause]
  (if (or (fn? clause) (keyword? clause))
    (fn [entity] (clause entity))
    (let [[a v] clause]
      (cond
        ;; arbitrary function
        (fn? v) (fn [entity] (v (get entity a)))
        ;; refs - resolve `v` lookup-refs & compare to :db/id of the thing
        (rp/ref? db a) (fn [entity] (= (resolve-v conn db a v) (:db/id (get entity a))))
        :else (fn [entity] (= (get entity a) v))))))

(defn where
  ([clauses] (where *conn* (current-db) clauses))
  ([conn db clauses]
   (assert db)
   ;; first clause reads from db
   (let [[clause & clauses] clauses
         _ (assert (not (fn? clause)) "where cannot begin with function (scans entire db)")
         entity-ids  (if (keyword? clause)
                       (ae conn db clause)
                       (let [[a v] clause]
                         (if (fn? v)
                           (filterv #(v (eav conn db % a))
                                    (ae conn db a))
                           (ave conn db a v))))]
     (->> entity-ids
          (into #{}
                (comp (map entity)
                      ;; additional clauses filter entities from step 1
                      (filter (if (seq clauses)
                                (apply every-pred (map #(clause-filter conn db %) clauses))
                                identity))))))))

(comment
 ;; should this exist? mem-only...
 (defmacro branch
   "Evaluates body with *conn* bound to a fork of `conn`. Returns a transaction."
   [conn & body]
   (let [[conn body] (if (or (symbol? conn) (map? conn))
                       [conn body]
                       ['re-db.read/*conn* (cons conn body)])]
     `(binding [*conn* (mem/clone ~conn)
                ~'re-db.in-memory/*branch-tx-log* (atom [])]
        (let [db-before# @*conn*
              val# (do ~@body)
              txs# @~'re-db.in-memory/*branch-tx-log*]
          (with-meta {:db-before db-before#
                      :db-after @*conn*
                      :datoms (into [] (mapcat :datoms) txs#)}
                     {:value val#}))))))