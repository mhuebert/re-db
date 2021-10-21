(ns re-db.core
  (:refer-clojure :exclude [indexed?])
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.util :refer [guard set-replace]]
            [re-db.schema :as schema]))

(def ^boolean index-all-ave? false)
(def ^boolean index-all-ae? false)

(js/console.info "re-db/index-all:"
                 (str (cond-> #{}
                              index-all-ave? (conj :ave)
                              index-all-ae? (conj :ae))))

;; used to log datoms while building up a tx-report
(def ^:dynamic *datoms* nil)
;; used to concatenate transactions while working on a branch
(def ^:dynamic *branch-tx-log* nil)



(def conj-set (fnil conj #{}))
(def into-set (fnil into #{}))

;; Schema properties are mirrored in javascript properties for fast lookups

;; Attribute-schema lookups (fast path)
(defn ave? [a-schema] (true? (j/!get a-schema :ave?)))
(defn ae? [a-schema] (true? (j/!get a-schema :ae?)))
(defn many? [a-schema] (true? (j/!get a-schema :many?)))
(defn unique? [a-schema] (true? (j/!get a-schema :unique?)))
(defn ref? [a-schema] (true? (j/!get a-schema :ref?)))

(defn resolve-lookup-ref [[a v] db]
  (when v
    (if (vector? v)
      (resolve-lookup-ref [a (resolve-lookup-ref v db)] db)
      (first (fast/get-in db [:ave a v])))))

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
      (throw (js/Error. (unique-err-msg a v e coll)))
      #{e})))

;; when the schema is normalized - we make lists of per-value and per-datom
;; indexers - which all datoms are looped through.
(defn ave-indexer [schema]
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

(defn ae-indexer [a-schema]
  {:index :ae
   :per :datom
   :f (if (::default? a-schema)
        (fn [db e a v pv v? pv?]
          (ae-indexer* db e a v pv v? pv? (many? (get-schema db a))))
        (let [is-many (many? a-schema)]
          (fn [db e a v pv v? pv?]
            (ae-indexer* db e a v pv v? pv? is-many))))})

(defn make-indexer [a-schema]
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
        (fn [db [e ^keyword a v pv]]
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
                            db))))))))))

(defn compile-a-schema* [{:as a-schema
                          :db/keys [index unique cardinality valueType db/index-ae]}]
  (let [unique? (boolean unique)
        index (or index-all-ave? index)
        ae (or index-all-ae? index-ae)
        a-schema (j/assoc! a-schema
                           :ave? (boolean (or index unique?))
                           :many? (= cardinality :db.cardinality/many)
                           :unique? unique?
                           :ref? (= valueType :db.type/ref)
                           :ae? ae)]
    (j/assoc! a-schema :index-fn (make-indexer a-schema))))


;; Lookup functions

(def default-schema (compile-a-schema* {::default? true}))

(defn get-schema [db a] (or (fast/get-in db [:schema a]) default-schema))
(defn get-entity [db e] (fast/get-in db [:eav e]))

(defn fqn [^keyword k] (.-fqn k))
(defn- update-indexes [db datom schema]
  (if (identical? "db/id" (fqn (aget datom 1)))
    db
    (if-some [index (j/!get schema :index-fn)]
      (index db datom)
      db)))

(defn index [db schema e a v pv]
  (let [datom #js[e a v pv]]
    (some-> *datoms* (j/push! datom))
    (update-indexes db datom schema)))

;; transactions

(defn- clear-empty-ent [db e]
  (cond-> db
          (empty? (fast/get-in db [:eav e]))
          (fast/update! :eav dissoc! e)))

(defn- retract-attr
  [db [_ e a v]]
  (if-some [pv (fast/get-in db [:eav e a])]
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
  #js[e a pv v])

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
  (if (some? (:db/id m))
    m
    (let [e-from-attr (e-from-unique-attr db m)]
      (assert (not (false? e-from-attr)) (str "must have a unique attribute" m))
      (assoc m :db/id (or e-from-attr (gen-e))))))

(defn- add-attr-index [[db m :as state] e a pv a-schema]
  (let [v (m a)
        is-many (many? a-schema)]
    (if-some [[v pv] (if is-many
                       (let [v (set/difference v pv)
                             pv (set/difference pv v)]
                         (when (or (seq v) (seq pv))
                           [v pv]))
                       (when (not= v pv)
                         [v pv]))]
      [(index db a-schema e a v pv) m]
      state)))

(declare add-map)

(defn resolve-attr-refs [[db m :as state] a v a-schema]
  (let [[db newv]
        (if (many? a-schema)
          (reduce
           (fn [[db vs :as state] v]
             (cond
               ;; don't rewrite lookup refs - late-bind
               #_#_(vector? v)
               [db (set-replace vs v (or (resolve-e v db) v))]

               (map? v)
               (let [sub-entity (resolve-map-e db v)]
                 [(add-map db sub-entity)
                  (set-replace vs v (:db/id sub-entity))])
               :else state))
           [db v]
           v)
          (cond (vector? v)
                [db (or (resolve-e v db) v)]
                (map? v)
                (let [sub-entity (resolve-map-e db v)]
                  [(add-map db sub-entity)
                   (:db/id sub-entity)])
                :else [db v]))]
    (if (identical? v newv)
      state
      [db (assoc m a newv)])))

(defn- add-map
  [db m]
  (let [{:as m e :db/id} (resolve-map-e db m)]
    (let [pm (get-entity db e)
          db-schema (:schema db)
          [db m] (reduce-kv (fn [state a v]
                              (let [a-schema (db-schema a default-schema)]
                                (-> state
                                    (cond-> (ref? a-schema)
                                            (resolve-attr-refs a v a-schema))
                                    (add-attr-index e a (get pm a) a-schema))))
                            [db m]
                            m)]
      (fast/update! db :eav assoc! e (cond->> m pm (merge pm))))))

(defn- commit-tx [db tx]
  (if (vector? tx)
    (case (tx 0)
      :db/add (add db (update tx 1 resolve-e db))
      :db/retractEntity (retract-entity db (update tx 1 resolve-e db))
      :db/retract (retract-attr db (update tx 1 resolve-e db))
      :db/datoms (reduce commit-datom db (tx 1))
      :db/datoms-reverse (->> (tx 1)
                              (reverse)
                              (map reverse-datom)
                              (reduce commit-datom db))
      (throw (js/Error (str "No db op: " (tx 0)))))
    (add-map db (update tx :db/id resolve-e db))))

(defn transient-map [m] (reduce-kv #(fast/update! %1 %2 transient) (transient m) m))
(defn persistent-map! [m] (let [m (persistent! m)]
                            (reduce-kv #(update %1 %2 persistent!) m m)))

(defn- transaction [db-before new-txs]
  (let [db-after (-> (reduce commit-tx (transient-map db-before) new-txs)
                     (persistent-map!))]
    {:db-before db-before
     :db-after db-after
     :datoms (or (some-> *datoms* vec) [])}))

(def ^:dynamic *prevent-notify* false)

(defn unlisten! [conn key]
  (swap! conn update :listeners dissoc key)
  nil)

(defn listen! [conn key f]
  (swap! conn assoc-in [:listeners key] f)
  #(unlisten! conn key))

(defn commit!
  ;; separating out the "commit" step because we may extend `transaction` to support
  ;; transaction functions and arbitrary effects
  "Commits a tx-report to conn"
  [conn {:as tx-report :keys [db-after]} {:keys [notify-listeners?]
                                          :or {notify-listeners? true}}]

  (reset! conn db-after)

  (when *branch-tx-log*
    (swap! *branch-tx-log* conj tx-report))

  (when (and notify-listeners? (not *prevent-notify*))
    (doseq [f (vals (:listeners db-after))]
      (f conn tx-report))))

(defonce tx-num (volatile! 0))
(defn transact!
  ([conn txs] (transact! conn txs {}))
  ([conn txs {:as opts
              :keys [notify-listeners?
                     report-datoms?]
              :or {notify-listeners? true}}]
   (when-let [tx-report (binding [*datoms* (when (boolean (or notify-listeners? report-datoms?)) #js[])]
                          (some-> (cond (nil? txs) nil
                                        (:datoms txs) txs
                                        (sequential? txs) (transaction @conn txs)
                                        :else (throw (js/Error "Transact! was not passed a valid transaction")))
                                  (assoc :tx (vswap! tx-num inc))))]
     (commit! conn tx-report opts)
     tx-report)))

(defn compile-a-schema [db-schema a a-schema]
  (if (= a :db/uniques)
    db-schema
    (let [a-schema (compile-a-schema* a-schema)]
      (-> (assoc db-schema a a-schema)
          (update :db/uniques (if (unique? a-schema) (fnil conj #{}) disj) a)))))

(defn compile-db-schema [schema]
  (->> (assoc schema :db/ident {:db/unique :db.unique/identity})
       (reduce-kv compile-a-schema {})))

(defn transient-1 [m k] (transient (update m k transient)))
(defn persistent-1 [m k] (update (persistent! m) k persistent!))

(defn add-missing-index [db a indexk]
  (let [{:as db
         :keys [schema]} (update db :schema
                                 (fn [db-schema]
                                   (compile-a-schema db-schema a
                                                     (merge (db-schema a)
                                                            (case indexk
                                                              :ae schema/ae
                                                              :ave schema/ave)))))
        a-schema (schema a)
        many? (many? a-schema)
        {:keys [f per]} ((case indexk :ae ae-indexer :ave ave-indexer) a-schema)]
    (-> (reduce-kv (if (and many? (= per :value))
                     (fn [db e m]
                       (reduce (fn [db v] (f db e a v nil true false)) db (m a)))
                     (fn [db e m]
                       (if-some [v (m a)]
                         (f db e a v nil true false)
                         db)))
                   (transient-1 db indexk)
                   (:eav db))
        (persistent-1 indexk))))

(defn merge-schema!
  "Merge additional schema options into a db. Indexes are not created for existing data."
  [db schema]
  (swap! db update :schema (comp compile-db-schema (partial merge-with merge)) schema))

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
