(ns re-db.in-memory
  (:refer-clojure :exclude [clone])
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.util :as u :refer [set-replace set-diff]]
            [re-db.schema :as schema]))

(def index-all-ave? false)
(def index-all-ae? false)
(def auto-index? true)

(defrecord Schema [ave many unique ref ae index-fn schema-map])

;; accessors with boolean type hint via u/bool
(defn unique? [^Schema s] (u/bool (.-unique s)))
(defn many? [^Schema s] (u/bool (.-many s)))
(defn ref? [^Schema s] (u/bool (.-ref s)))
(defn ave? [^Schema s] (u/bool (.-ave s)))
(defn ae? [^Schema s] (u/bool (.-ae s)))

(comment
 (#?(:cljs js/console.info
     :clj  prn) "re-db/index-all:"
                (str (cond-> #{}
                             index-all-ave? (conj :ave)
                             index-all-ae? (conj :ae)))))

;; used to log datoms while building up a tx-report
(def ^:dynamic *datoms* nil)
;; used to concatenate transactions while working on a branch
(def ^:dynamic *branch-tx-log* nil)

(def conj-set (fnil conj #{}))
(def into-set (fnil into #{}))

;; Schema properties are mirrored in javascript properties for fast lookups

(defn resolve-lookup-ref [db [a v]]
  (when v
    (if (vector? v)
      (resolve-lookup-ref db [a (resolve-lookup-ref db v)])
      (first (fast/gets db :ave a v)))))


(defonce last-e (volatile! 0.1))
(defn gen-e [] (vswap! last-e inc))

;; generate internal db uuids
(defn tempid? [x] (neg-int? x))

(defn resolve-e
  "Returns entity id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [db e]
  (assert (not (tempid? e)) (str "invalid use of tempid: " e))
  (if (vector? e)
    (resolve-lookup-ref db e)
    e))

(declare add)

(defn tempid+ [db e]
  (if-let [id (get (:tempids db) e)]
    [db id]
    (let [id (gen-e)]
      [(fast/update! db :tempids assoc! e id)
       id])))

(defn resolve-lookup-ref+ [db e]
  (if-let [id (resolve-lookup-ref db e)]
    [db id]
    ;; upsert lookup ref
    (let [[a v] e
          id (gen-e)]
      [(add db [nil id a v])
       id])))

(defn resolve-e+
  "Returns entity id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [db e]
  (cond (vector? e) (resolve-lookup-ref+ db e)
        (tempid? e) (tempid+ db e)
        #_#_(keyword? e) (resolve-lookup-ref+ db [:db/ident e])
        :else [db e]))

;; index helpers

(defn unique-err-msg [a v e pe]
  (str "Unique index on " a
       "; attempted to write duplicate value " v " on id " e
       " but already have " pe "."))

(defn set-unique!
  "Returns a checked setter for unique attributes`
  - throws a meaningful error if encounters a non-empty set"
  [a v]
  (fn [coll e]
    (if (seq coll)
      (throw (ex-info "Attempted to write duplicate e value on unique index"
                      {:unique-av [a v]
                       :found coll
                       :new #{e}}))
      #{e})))

;; when the schema is normalized - we make lists of per-value and per-datom
;; indexers - which all datoms are looped through.
(defn ave-indexer [^Schema schema]
  (let [is-unique (unique? schema)]
    {:index :ave
     :per :value
     :f (fn [db e a v pv v? pv?]
          (cond-> db
                  v?
                  (fast/update-db-index! [:ave a v]
                                         (if is-unique
                                           (set-unique! a v)
                                           conj-set)
                                         e)
                  pv?
                  (fast/update-db-index! [:ave a pv] disj e)))
     :add (fn [db e a v]
            (fast/update-db-index! db [:ave a v]
                                   (if is-unique
                                     (set-unique! a v)
                                     conj-set)
                                   e))
     :remove (fn [db e a pv]
               (fast/update-db-index! db [:ave a pv] disj e))}))

(declare get-entity get-schema)
(defn ae-indexer* [db e a v pv v? pv? is-many]
  (let [exists (or v?
                   (and is-many (seq (get (get-entity db e) a))))]
    (if exists
      (fast/update! db :ae fast/update-seq! a conj-set e)
      (fast/update! db :ae fast/update-seq! a disj e))))

(defn ae-indexer [^Schema a-schema]
  {:index :ae
   :per :datom
   :f (if (::default? a-schema)
        (fn [db e a v pv v? pv?]
          (ae-indexer* db e a v pv v? pv? (many? (get-schema db a))))
        (let [is-many (many? a-schema)]
          (fn [db e a v pv v? pv?]
            (ae-indexer* db e a v pv v? pv? is-many))))})

(def make-indexer
  (memoize
   (fn [^Schema a-schema]
     ;; one indexer per attribute
     (let [[per-value per-datom] (->> (cond-> []
                                              (ave? a-schema) (conj (ave-indexer a-schema))
                                              (ae? a-schema) (conj (ae-indexer a-schema)))
                                      (reduce (fn [out {:keys [per f]}]
                                                (case per
                                                  :value (update out 0 conj f)
                                                  :datom (update out 1 conj f)))
                                              [[] []]))
           is-many? (many? a-schema)]
       (when (or (seq per-value) (seq per-datom))
         (let [per-values (fast/comp6 per-value)
               per-datoms (fast/comp6 per-datom)]
           (fn [db [e a v pv]]
             (let [v? (boolean (if is-many? (seq v) (some? v)))
                   pv? (boolean (if is-many? (seq pv) (some? pv)))]
               (as-> db db
                     ;; per-datom
                     (per-datoms db e a v pv v? pv?)

                     ;; per-value
                     ;; loop per-value fns through cardinality/many sets
                     (if (not is-many?)
                       (per-values db e a v pv v? pv?)
                       (as-> db db
                             ;; additions
                             (if v?
                               (reduce
                                (fn [db v1]
                                  (per-values db e a v1 nil true false)) db v)
                               db)
                             ;; removals
                             (if pv?
                               (reduce
                                (fn [db pv1]
                                  (per-values db e a nil pv1 false true)) db pv)
                               db))))))))))))

(defn compile-a-schema* ^Schema [{:as schema-map :db/keys [index unique cardinality valueType index-ae]}]
  (let [unique? (boolean unique)
        index (or index-all-ave? index)
        ae (boolean (or index-all-ae? index-ae))
        a-schema (map->Schema
                  {:ave (boolean (or index unique?))
                   :many (= cardinality :db.cardinality/many)
                   :unique unique?
                   :ref (= valueType :db.type/ref)
                   :ae ae
                   :schema-map schema-map})]
    (assoc a-schema :index-fn (make-indexer a-schema))))

;; Lookup functions

(def default-schema (compile-a-schema* {::default? true}))

(defn get-schema ^Schema [db a] (or (fast/gets db :schema a) default-schema))
(defn get-entity [db e] (fast/gets db :eav e))

(defn- update-indexes [db datom ^Schema schema]
  (if (= :db/id #?(:cljs (aget datom 1)
                   :clj  (datom 1)))
    db
    (if-some [index (:index-fn schema)]
      (index db datom)
      db)))

(defn index [db schema e a v pv]
  (let [datom (#?(:cljs array :clj vector) e a v pv)]
    (when *datoms*
      #?(:cljs (j/push! *datoms* datom)
         :clj  (vswap! *datoms* conj datom)))
    (update-indexes db datom schema)))

;; transactions

(defn- clear-empty-ent [db e]
  (cond-> db
          (empty? (fast/gets db :eav e))
          (fast/update! :eav dissoc! e)))

(defn- retract-attr
  [db [_ e a v]]
  (let [a-schema (get-schema db a)
        is-many (many? a-schema)
        is-ref (ref? a-schema)
        v (cond->> v is-ref (resolve-e db))]
    (if-some [pv (fast/gets db :eav e a)]
      (if is-many
        (do
          (assert (not (set? v)) ":db/retract on :db.cardinality/many does not accept a set")
          (if (contains? pv v)
            (-> db
                (fast/update-db-index! [:eav e a] disj v)
                (index a-schema e a nil #{v}))
            db))
        (-> db
            (fast/dissoc-db-index! [:eav e a])
            (index a-schema e a nil pv)
            (clear-empty-ent e)))
      db)))

;; retracts each attribute in the entity
(defn- retract-entity [db [_ e]]
  (reduce-kv (fn [db a v]
               (if (many? (get-schema db a))
                 (reduce (fn [db x]
                              (retract-attr db [nil e a x]))
                            db
                            v)
                 (retract-attr db [nil e a v])))
             db
             (get-entity db e)))

(defn- add
  [db [_ e a v]]
  (let [a-schema (get-schema db a)
        is-many (many? a-schema)
        is-ref (ref? a-schema)
        pm (get-entity db e)
        pv (get pm a)
        [db v] (if is-ref (resolve-e+ db v) [db v])]
    (if is-many
      (do
        (assert (not (set? v)) ":db/add on :db.cardinality/many does not accept a set")
        (if (contains? pv v)
          db
          (-> db
              (fast/update-db-index! [:eav e a] conj-set v)
              (index a-schema e a #{v} nil))))
      (if (= pv v)
        db
        (-> db
            (fast/assoc-db-index! [:eav e a] v)
            (index a-schema e a v pv))))))

(j/defn commit-datom [db ^js [e a v pv]]
  (let [a-schema (get-schema db a)]
    (-> (if (many? a-schema)
          ;; for cardinality/many, `v` is a set of "additions" and `pv` is a set of "removals"
          (fast/update-db-index! db
                                 [:eav e a]
                                 (fn [dbv]
                                   (-> (into (or dbv #{}) v)
                                       (set/difference pv))))
          (if (some? v)
            (fast/assoc-db-index! db [:eav e a] v)
            (fast/dissoc-db-index! db [:eav e a])))
        (index a-schema e a v pv))))

(j/defn reverse-datom [^js [e a v pv]]
  (#?(:cljs array :clj vector) e a pv v))

(defn e-from-unique-attr
  "Finds an entity id by looking for a unique attribute in `m`.

   Special return values:
   ::upsert   => a unique attribute was found, but no matching entry in the db
   ::missing-unique-attribute => no unique attributes found in `m`"
  [db m]
  (let [uniques (-> db :schema :db/uniques)
        e (reduce (fn [ret a]
                    (fast/if-found [v (m a)]
                      (if-some [e (resolve-lookup-ref db [a v])]
                        (reduced e)
                        ::upsert)
                      ret))
                  ::missing-unique-attribute
                  uniques)]
    (case e ::upsert (gen-e)
            ::missing-unique-attribute (throw (ex-info "Missing unique attribute" {:entity-map m}))
            e)))

(defn- add-attr-index [db e a v pv ^Schema a-schema]
  (let [is-many (many? a-schema)]
    (if-some [[v pv] (if is-many
                       (set-diff pv v)
                       (when (not= v pv)
                         [v pv]))]
      (index db a-schema e a v pv)
      db)))

(declare add-map)

(defn handle-lookup-ref [db v]
  ;; if a lookup ref doesn't resolve,
  ;; treat it as an upsert (map)
  (if (vector? v)
    (or (resolve-lookup-ref db v)
        {(v 0) (v 1)})
    v))

(defn- resolve-map-e [db m]
  (if-let [e (:db/id m)]
    (conj (resolve-e+ db e) (dissoc m :db/id))
    [db (e-from-unique-attr db m) m]))

(defn handle-nested-refs [db v ^Schema a-schema]
  ;; 'refs' can update the db in a handful of ways:
  ;; - tempids,
  ;; - map upserts,
  ;; - lookup ref upserts
  (let [resolve-ref (fn [db v]
                      (if (map? v)
                        (let [[db e sub-entity] (resolve-map-e db v)]
                          [(add-map db e sub-entity)
                           e])
                        (resolve-e+ db v)))]
    (if (many? a-schema)
      (reduce
       (fn [[db vs] v]
         (let [[db e] (resolve-ref db v)]
           [db (conj vs e)]))
       [db (empty v)]
       v)
      (resolve-ref db v))))

(defn- add-map
  ([db m]
   (let [[db e m] (resolve-map-e db m)]
     (add-map db e m)))
  ([db e m]
   (let [prev-m (get-entity db e)
         db-schema (:schema db)
         [db new-m] (reduce-kv (fn [[db new-m] a v]
                                 (let [reverse? (u/reverse-attr? a)
                                       canonical-a (u/forward-attr a)
                                       a-schema (db-schema canonical-a default-schema)]
                                   (if reverse?
                                     [(reduce (fn [db v]
                                                (if (map? v)
                                                  (let [[db ve m] (resolve-map-e db v)]
                                                    (-> db
                                                        (add [nil ve canonical-a e])
                                                        (add-map ve m)))
                                                  (let [[db ve] (resolve-e+ db v)]
                                                    (add db [nil ve canonical-a e])))) db v) new-m]
                                     (let [is-ref (ref? a-schema)
                                           is-many (many? a-schema)
                                           v (cond-> v is-many set)
                                           ;; if ref attribute, handle inline/nested entities
                                           [db new-v] (if is-ref
                                                        (handle-nested-refs db v a-schema)
                                                        [db v])
                                           ;; update indexes
                                           db (add-attr-index db e a new-v (get prev-m a) a-schema)
                                           value-present (if is-many
                                                           (seq new-v)
                                                           (some? new-v))]
                                       [db (if value-present
                                             (assoc new-m a new-v)
                                             (dissoc new-m a))]))))
                               [db (or prev-m m)]
                               m)]
     (fast/update! db :eav assoc! e new-m))))

(defn- commit-tx [db tx]
  (if (vector? tx)
    (let [operation (tx 0)]
      (case operation
        :db/add (let [e (tx 1)
                      _ (when-not e (throw (ex-info "db/id not present in tx" {:tx tx})))
                      [db resolved-e] (resolve-e+ db e)]
                  (add db (assoc tx 1 resolved-e)))
        :db/retractEntity (retract-entity db (update tx 1 #(resolve-e db %)))
        :db/retract (retract-attr db (update tx 1 #(resolve-e db %)))
        :db/datoms (reduce commit-datom db (tx 1))
        :db/datoms-reverse (->> (tx 1)
                                (reverse)
                                (map reverse-datom)
                                (reduce commit-datom db))
        (if-let [op (fast/gets db :schema :db/tx-fns operation)]
          (reduce commit-tx db (op db tx))
          (throw (ex-info "db operation does not exist" {:op operation})))))
    (add-map db tx)))


(defn transient-k [m k] (transient (update m k transient)))
(defn persistent-k [m k] (update (persistent! m) k persistent!))
(defn transient-map [m] (reduce-kv (fn [m k _v] (fast/update! m k transient)) (transient m) m))
(defn persistent-map! [m] (let [m (persistent! m)] (reduce-kv (fn [m k _v] (update m k persistent!)) m m)))

(def ^:dynamic *prevent-notify* false)

(defn unlisten! [conn key]
  (swap! conn update :listeners dissoc key)
  nil)

(defn listen! [conn key f]
  (swap! conn assoc-in [:listeners key] f)
  #(unlisten! conn key))

(defonce !tx-clock (atom 0))

(defn transaction
  [db-before txs options]
  {:pre [db-before (or (nil? txs) (sequential? txs))]}
  (binding [*datoms* (when (:notify-listeners? options true) #?(:cljs #js[] :clj (volatile! [])))]
    (let [db-after (-> (reduce commit-tx (transient-map (-> db-before
                                                            (dissoc :tx)
                                                            (assoc :tempids {}))) txs)
                       (persistent-map!))
          datoms (if *datoms*
                   #?(:cljs (vec *datoms*)
                      :clj  @*datoms*)
                   [])]
      (assoc options
        :db-before db-before
        :db-after (if (seq datoms)
                    db-after
                    db-before)
        :datoms datoms))))

(defn commit!
  ;; separating out the "commit" step because we may extend `transaction` to support
  ;; transaction functions and arbitrary effects
  "Commits a tx-report to !conn"
  ([!conn tx-report] (commit! !conn tx-report {}))
  ([!conn tx-report options]
   (let [tx (swap! !tx-clock inc)
         tx-report (-> tx-report
                       (assoc :tx tx)
                       (assoc-in [:db-after :tx] tx))]
     (when (seq (:datoms tx-report))
       (reset! !conn (:db-after tx-report))

       (when *branch-tx-log*
         (swap! *branch-tx-log* conj tx-report))

       (when (and (:notify-listeners? options true)
                  (not *prevent-notify*))
         (doseq [f (-> tx-report :db-after :listeners vals)]
           (f !conn tx-report)))
       tx-report))))

(defn transact!
  "Transacts txs and returns a tx-report"
  ([conn txs] (transact! conn txs {}))
  ([conn txs opts]
   (commit! conn (transaction @conn txs opts) opts)))

(defn compile-a-schema [db-schemas a a-schema-map]
  (if (or (= :db/ident a) (not= "db" (namespace a)))
    (let [^Schema a-schema (compile-a-schema* a-schema-map)]
      (-> (assoc db-schemas a a-schema)
          (update :db/uniques (if (unique? a-schema) (fnil conj #{}) disj) a)))
    (assoc db-schemas a a-schema-map)))

(defn compile-db-schema [schema]
  (->> (assoc schema :db/ident {:db/unique :db.unique/identity})
       (reduce-kv compile-a-schema {})))

(defn add-missing-index [db a indexk]
  (let [{:as db
         :keys [schema]} (update db :schema
                                 (fn [db-schema]
                                   (let [changes (case indexk
                                                   :ae schema/ae
                                                   :ave schema/ave)]
                                     (-> db-schema
                                         (compile-a-schema a
                                                           (merge (:schema-map (get db-schema a))
                                                                  changes))
                                         (update :db/runtime-changes conj {a changes})))))
        a-schema ^Schema (get schema a)
        is-many (many? a-schema)
        {:keys [f per]} ((case indexk :ae ae-indexer :ave ave-indexer) a-schema)]
    (-> (reduce-kv (if (and is-many (= per :value))
                     (fn [db e m]
                       (reduce (fn [db v] (f db e a v nil true false)) db (m a)))
                     (fn [db e m]
                       (if-some [v (m a)]
                         (f db e a v nil true false)
                         db)))
                   (transient-k db indexk)
                   (:eav db))
        (persistent-k indexk))))

(defn merge-schema!
  "Merge additional schema options into a db. Does not update indexes for existing data.
   Any attribute present in `schema` will replace existing schema for that attribute."
  [conn schema]
  (swap! conn update :schema (fn [old-schema]
                               (reduce-kv compile-a-schema old-schema schema))))

(defn create-conn
  "Create a new db, with optional schema, which should be a mapping of attribute keys to
  the following options:

    :db/index       [true, :db.index/unique]
    :db/cardinality [:db.cardinality/many]"
  ([] (create-conn {}))
  ([schema]
   (atom {:ae {}
          :eav {}
          :ave {}
          :schema (compile-db-schema schema)})))

(defn clone
  "Creates a copy of conn"
  [conn]
  (atom @conn))

(defn ->conn
  "Accepts a conn or a schema, returns conn"
  [conn-or-schema]
  (if (map? conn-or-schema)
    (create-conn conn-or-schema)
    conn-or-schema))