(ns re-db.core
  (:refer-clojure :exclude [indexed?])
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.util :refer [guard set-replace]]))

(def ^boolean index-all-ave? false)
(def ^boolean index-all-ae? true)

(js/console.info "re-db/index-all:"
                 (str (cond-> #{}
                              index-all-ave? (conj :ave)
                              index-all-ae? (conj :ae))))

(def ^:dynamic *datoms* nil)

(def conj-set (fnil conj #{}))
(def into-set (fnil into #{}))

;; Schema properties are mirrored in javascript properties for fast lookups

;; Attribute-schema lookups (fast path)
(defn indexed? [a-schema] (true? (j/!get a-schema :ave?)))
(defn ae? [a-schema] (true? (j/!get a-schema :ae?)))
(defn many? [a-schema] (true? (j/!get a-schema :many?)))
(defn unique? [a-schema] (true? (j/!get a-schema :unique?)))
(defn ref? [a-schema] (true? (j/!get a-schema :ref?)))

;; schema
(def indexed-many {:db/cardinality :db.cardinality/many})
(def indexed-ref {:db/valueType :db.type/ref})
(def indexed-unique {:db/unique :db.unique/identity})
(def indexed-unique-value {:db/unique :db.unique/value})
(def indexed-ave {:db/index true})
(def indexed-ae {:db.index/_a_ true})

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

(defn set-unique
  "Returns a checked setter for unique attributes
  - throws a meaningful error if encounters a non-empty set"
  [a v]
  (fn [coll e]
    (if (seq coll)
      (throw (js/Error. (unique-err-msg a v e coll)))
      #{e})))

;; when the schema is normalized - we make lists of per-value and per-datom
;; indexers - which all datoms are looped through.
(defn ave-indexer [schema]
  (when (or index-all-ave? (indexed? schema))
    {:name :ave
     :per :value
     :f (fn [ave e a v pv ^boolean some-v? ^boolean some-pv?]
          (cond-> ave
                  some-v?
                  (fast/update-index! [a v] (if (unique? schema)
                                              (set-unique a v)
                                              conj-set) e)
                  some-pv?
                  (fast/update-index! [a pv] disj e)))}))

(defn vae-indexer [schema]
  (when (ref? schema)
    {:name :vae
     :per :value
     :f (fn [vae e a v pv ^boolean some-v? ^boolean some-pv?]
          (cond-> vae
                  some-v?
                  (fast/update-index! [v a] conj-set e)
                  some-pv?
                  (fast/update-index! [pv a] disj e)))}))

(defn ae-indexer [schema]
  (when (or index-all-ae? (ae? schema))
    {:name :ae
     :per :datom
     :f (fn [ae e a v pv ^boolean some-v? _]
          (let [i (or (ae a) #{})
                found? (i e)]
            (if (identical? some-v? found?)
              ae
              (let [i (if some-v? (conj i e) (disj i e))]
                (if (seq i)
                  (assoc! ae a i)
                  (dissoc! ae a))))))}))

(defn to-db [f k]
  (fn [db e a v pv some-v? some-pv?]
    (fast/update! db k f e a v pv some-v? some-pv?)))

(defn make-indexer [schema]
  ;; one indexer per attribute
  (let [[per-values
         per-datoms] (->> [ave-indexer
                           vae-indexer
                           ae-indexer]
                          (keep #(% schema))
                          (reduce (fn [out {:as i :keys [per f name]}]
                                    (case per
                                      :value (update out 0 conj (to-db f name))
                                      :datom (update out 1 conj (to-db f name))))
                                  [[] []]))
        is-many? (many? schema)]
    (when (or (seq per-values) (seq per-datoms))
      (let [per-values (fast/comp6 per-values)
            per-datoms (fast/comp6 per-datoms)]
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
                          :db/keys [index unique cardinality valueType]}]
  (let [unique? (boolean unique)
        a-schema (j/assoc! a-schema
                           :ave? (boolean (or index unique?))
                           :many? (= cardinality :db.cardinality/many)
                           :unique? unique?
                           :ref? (= valueType :db.type/ref)
                           :ae? (:db.index/_a_ a-schema))]
    (j/assoc! a-schema :index-fn (make-indexer a-schema))))


;; Lookup functions

(def default-schema
  (compile-a-schema* (cond-> {}
                             index-all-ave? (merge indexed-ave)
                             index-all-ae? (merge indexed-ae))))

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
  (let [is-many (many? a-schema)]
    (if-some [[v pv] (let [v (get m a)]
                       (if is-many
                         (let [v (set/difference v pv)
                               pv (set/difference pv v)]
                           (when (or (seq v) (seq pv))
                             [v pv]))
                         (when (not= v pv)
                           [v pv])))]
      [(index db a-schema e a v pv) m]
      state)))

(declare add-map)

(defn resolve-attr-refs [[db m :as state] a v a-schema]
  (let [[db nv]
        (if (many? a-schema)
          (reduce
           (fn [[db vs :as state] v]
             (cond (vector? v)
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
    (if (identical? v nv)
      state
      [db (assoc m a nv)])))

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
      (fast/update! db :eav assoc! e m))))

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

(defn transient-db [db]
  (-> (transient db)
      (fast/update! :ae transient)
      (fast/update! :eav transient)
      (fast/update! :ave transient)
      (fast/update! :vae transient)))

(defn persistent-db! [db]
  (-> db
      (fast/update! :ae persistent!)
      (fast/update! :eav persistent!)
      (fast/update! :ave persistent!)
      (fast/update! :vae persistent!)
      persistent!))

(defn- transaction [db-before new-txs]
  (let [db-after (-> (reduce commit-tx (transient-db db-before) new-txs)
                     (persistent-db!))]
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

(defn transact!
  ([conn txs] (transact! conn txs {}))
  ([conn txs {:keys [notify-listeners?
                     report-datoms?]
              :or {notify-listeners? true}}]
   (binding [*datoms* (when (boolean (or notify-listeners? report-datoms?))
                        #js[])]
     (when-let [{:keys [db-after] :as tx} (cond (nil? txs) nil
                                                (:datoms txs) txs
                                                (sequential? txs) (transaction @conn txs)
                                                :else (throw (js/Error "Transact! was not passed a valid transaction")))]
       (reset! conn db-after)
       (when (and notify-listeners? (not *prevent-notify*))
         (doseq [f (vals (:listeners db-after))]
           (f conn tx)))
       tx))))

(defn compile-a-schema [db-schema a {:as a-schema
                                     :db/keys [index unique cardinality valueType]}]
  (if (= a :db/uniques)
    db-schema
    (let [a-schema (compile-a-schema* a-schema)]
      (-> (assoc db-schema a a-schema)
          (update :db/uniques (if (unique? a-schema) (fnil conj #{}) disj) a)))))

(defn compile-db-schema [schema]
  (->> (assoc schema :db/ident {:db/unique :db.unique/identity})
       (reduce-kv compile-a-schema {})))

(defn add-missing-index [db a indexk]
  (let [{:as db :keys [schema]} (update db :schema (fn [db-schema]
                                                     (compile-a-schema db a (merge (db-schema a)
                                                                                   (case indexk
                                                                                     :ae indexed-ae
                                                                                     :ave indexed-ave)))))
        a-schema (schema a)
        ids (fast/get-in db [:ae a])
        eav (:eav db)
        indexer (:f ((case indexk :ae ae-indexer :ave ave-indexer) a-schema))
        index (db indexk)]
    (assoc db indexk
              (-> (reduce (fn [index e] (reduce-kv
                                         (fn [index a v]
                                           (indexer index e a v nil true false))
                                         index
                                         (eav e)))
                          (transient index)
                          ids)
                  persistent!))))

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
