(ns re-db.in-memory
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.util :as u :refer [guard set-replace set-diff]]
            [re-db.schema :as schema]
            [re-db.protocols :as rp]))

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

(defn resolve-lookup-ref [[a v] db]
  (when v
    (if (vector? v)
      (resolve-lookup-ref [a (resolve-lookup-ref v db)] db)
      (first (fast/gets db :ave a v)))))

(defn resolve-e
  "Returns entity id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [e db]
  (if (vector? e)
    (resolve-lookup-ref e db)
    e))

;; index helpers

(defn unique-err-msg [a v e pe]
  (str "Unique index on " a
       "; attempted to write duplicate value " v " on id " e
       " but already have " pe "."))

(defn set-unique!
  "Returns a checked setter for unique attributes
  - throws a meaningful error if encounters a non-empty set"
  [a v]
  (fn [coll e]
    (if (seq coll) #_(some-> coll (disj e) seq)
      (throw (#?(:cljs js/Error.
                 :clj  Exception.) (unique-err-msg a v e coll)))
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

(def vae-indexer
  (constantly
   {:index :vae
    :per :value
    :f (fn [db e a v pv v? pv?]
         (cond-> db
                 v? (fast/update-db-index! [:vae v a] conj-set e)
                 pv? (fast/update-db-index! [:vae pv a] disj e)))}))

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
                                              (ref? a-schema) (conj (vae-indexer a-schema))
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
  (if-some [pv (fast/gets db :eav e a)]
    (let [a-schema (get-schema db a)]
      (if (many? a-schema)
        (if-some [removals (guard (set/intersection v pv) seq)]
          (-> db
              (fast/update-db-index! [:eav e a] (fnil set/difference #{}) removals)
              (index a-schema e a nil removals))
          db)
        (-> db
            (fast/dissoc-db-index! [:eav e a])
            (index a-schema e a nil pv)
            (clear-empty-ent e))))
    db))

;; retracts each attribute in the entity
(defn- retract-entity [db [_ e]]
  (reduce-kv (fn [db a v]
               (retract-attr db [nil e a v]))
             db
             (get-entity db e)))

(defn- add
  [db [_ e a v]]
  (let [a-schema (get-schema db a)
        db (if (contains? (:eav db) e)
             db
             (fast/update! db :eav assoc! e {:db/id e}))
        m (get-entity db e)
        pv (get m a)
        v (cond-> v (ref? a-schema) (resolve-e db))]
    (if (many? a-schema)
      (if-some [additions (guard (set/difference v pv) seq)]
        (-> db
            (fast/update-db-index! [:eav e a] into-set additions)
            (index a-schema e a additions nil))
        db)
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

;; generate internal db uuids
(defonce last-e (volatile! 0.1))
(defn gen-e [] (vswap! last-e inc))

(defn e-from-unique-attr
  "Finds an entity id by looking for a unique attribute in `m`.

   Special return values:
   nil   => a unique attribute was found, but no matching entry in the db
   false => no unique attributes found in `m`"
  [db m]
  (let [uniques (-> db :schema :db/uniques)]
    (reduce (fn [ret a]
              (fast/if-found [v (m a)]
                (if-some [e (resolve-e [a v] db)]
                  (reduced e)
                  nil)
                ret)) false
            uniques)))

;; adds :db/id to entity based on unique attributes
(defn- resolve-map-e [db m]
  (let [e (:db/id m)]
    (cond (nil? e) (let [e-from-attr (e-from-unique-attr db m)]
                     (assert (not (false? e-from-attr)) (str "must have a unique attribute" m))
                     (assoc m :db/id (or e-from-attr (gen-e))))
          ;; support for lookup ref as :db/id. avoids iterating over keys to find a unique attribute
          ;; when we already know which attribute is unique/identity.
          (vector? e) (let [[a v] e]
                        (-> m
                            (assoc :db/id (or (resolve-e e db) (gen-e))
                                   a v)))
          :else m)))

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
  (if (vector? v)
    (or (resolve-e v db)
        {(v 0) (v 1)})
    v))

(defn handle-nested-entities [db v ^Schema a-schema]
  (if (many? a-schema)
    (reduce
     (fn [[db vs :as state] v]
       (let [v-resolved (handle-lookup-ref db v)]
         (if (map? v-resolved)
           (let [sub-entity (resolve-map-e db v-resolved)]
             [(add-map db sub-entity)
              (set-replace vs v (:db/id sub-entity))])
           state)))
     [db v]
     v)
    (let [v-resolved (handle-lookup-ref db v)]
      (if (map? v-resolved)
        (let [sub-entity (resolve-map-e db v-resolved)]
          [(add-map db sub-entity)
           (:db/id sub-entity)])
        [db v-resolved]))))

(defn- add-map
  [db m]
  (let [{:as m e :db/id} (resolve-map-e db m)]
    (let [prev-m (get-entity db e)
          db-schema (:schema db)
          [db new-m] (reduce-kv (fn [[db new-m] a v]
                                  (let [a-schema (db-schema a default-schema)
                                        ;; if ref attribute, handle inline/nested entities
                                        [db new-v] (if (ref? a-schema)
                                                     (handle-nested-entities db v a-schema)
                                                     [db v])
                                        ;; update indexes
                                        db (add-attr-index db e a new-v (get prev-m a) a-schema)
                                        value-present (if (many? a-schema)
                                                        (seq new-v)
                                                        (some? new-v))]
                                    [db (if value-present
                                          (assoc new-m a new-v)
                                          (dissoc new-m a))]))
                                [db (or prev-m m)]
                                m)]
      (fast/update! db :eav assoc! e new-m))))

(defn- commit-tx [db tx]
  (if (vector? tx)
    (let [operation (tx 0)]
      (case operation
        :db/add (let [e (tx 1)]
                  (if-let [resolved-e (resolve-e e db)]
                    (add db (assoc tx 1 resolved-e))
                    (do
                      ;; id cannot be resolved - upsert lookup ref
                      (assert (vector? e) "db/id missing")
                      (add-map db {(e 0) (e 1)
                                   (tx 2) (tx 3)}))))
        :db/retractEntity (retract-entity db (update tx 1 resolve-e db))
        :db/retract (retract-attr db (update tx 1 resolve-e db))
        :db/datoms (reduce commit-datom db (tx 1))
        :db/datoms-reverse (->> (tx 1)
                                (reverse)
                                (map reverse-datom)
                                (reduce commit-datom db))
        (if-let [op (fast/gets db :schema :db/tx-fns operation)]
          (reduce commit-tx db (op db tx))
          (do
            ;; TODO
            (prn :no-op-found (fast/gets db :schema :db/tx-fns operation))
            (throw (#?(:cljs js/Error :clj Exception.) (str "No db op: " operation)))))))
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
    (let [db-after (-> (reduce commit-tx (transient-map (dissoc db-before :tx)) txs)
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
          :vae {}
          :ave {}
          :schema (compile-db-schema schema)})))

(defn doto-triples [f {:keys [datoms db-after]}]
  (let [many? (memoize (fn [a] (many? (get-schema db-after a))))]
    (doseq [[e a v pv] datoms]
      (if (many? a)
        (do (doseq [v v] (f e a v))
            (doseq [pv pv] (f e a pv)))
        (do (when (some? v) (f e a v))
            (when (some? pv) (f e a pv)))))))

(defn- db-ave
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  ([db a v]
   (db-ave db a v (ave? (get-schema db a))))
  ([db a v indexed?]
   #_#_when-let [v (cond->> v (ref? a-schema) (resolve-e db))] ;; rp/ave must be called with a resolved value
   (if indexed?
     (fast/gets db :ave a v)
     (->> (:eav db)
          (reduce-kv
           (fn [out e m]
             (cond-> out
                     (= (m a) v)
                     (conj e)))
           #{})))))

(defn- conn-ave
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [conn a v]
  (let [a-schema (get-schema @conn a)]
    (when (and auto-index? (not (ave? a-schema)))
      (swap! conn add-missing-index a :ave))
    (db-ave @conn a v true)))

(extend-type #?(:clj clojure.lang.Atom :cljs cljs.core.Atom)
  rp/ITriple
  (db [conn] @conn)
  (as-map [conn e] (fast/gets @conn :eav e))
  (eav [conn e a] (fast/gets @conn :eav e a))
  (ave [conn a v] (conn-ave conn a v))
  (vae [conn v a] (prn :vae! v a) (fast/gets @conn :vae v a))
  (ae [conn a] (fast/gets @conn :ae a))
  (internal-e [conn e] e)
  (get-schema [conn a] (get-schema @conn a))
  (ref?
    ([conn a] (ref? (get-schema @conn a)))
    ([conn a schema] (ref? schema)))
  (unique?
    ([conn a] (unique? (get-schema @conn a)))
    ([conn a schema] (unique? schema)))
  (many?
    ([conn a] (many? (get-schema @conn a)))
    ([conn a schema] (many? schema)))
  (doto-triples [conn handle-triple report] (doto-triples handle-triple report)))

(extend-type default
  rp/ITriple
  (as-map [db e] (fast/gets db :eav e))
  (db [-db] -db)
  (eav [db e a] (fast/gets db :eav e a))
  (ave [db a v] (db-ave db a v))
  (vae [db v a] (prn :vae!2) (fast/gets db :vae v a))
  (ae [db a] (fast/gets db :ae a))
  (internal-e [db e] e)
  (get-schema [db a] (get-schema db a))
  (ref?
    ([db a] (ref? (get-schema db a)))
    ([db a schema] (ref? schema)))
  (unique?
    ([db a] (unique? (get-schema db a)))
    ([db a schema] (unique? schema)))
  (many?
    ([db a] (many? (get-schema db a)))
    ([db a schema] (many? schema)))
  (doto-triples [this handle-triple report] (doto-triples handle-triple report)))

