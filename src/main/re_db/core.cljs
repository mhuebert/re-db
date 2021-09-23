(ns re-db.core
  (:refer-clojure :exclude [indexed?])
  (:require [applied-science.js-interop :as j]
            [cljs-uuid-utils.core :as uuid-utils]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.util :refer [difference-in disj-in dissoc-in guard update-set]]))

(def ^:dynamic ^boolean *report-datoms?* true)              ;; if false, datoms are not tracked & listeners are not notified. faster.

(def conj-set (fnil conj #{}))
(def into-set (fnil into #{}))

;; Lookup functions

(defn get-schema [db a] (fast/get-in db [:schema a]))
(defn get-entity [db e] (fast/get-in db [:eav e]))

;; Schema properties are mirrored in javascript properties for fast lookups

;; Attribute-schema lookups (fast path)
(defn indexed? [attr-schema] (true? (j/get attr-schema :indexed?)))
(defn _a_? [attr-schema] (true? (j/get attr-schema :_a_?)))
(defn many? [attr-schema] (true? (j/get attr-schema :many?)))
(defn unique? [attr-schema] (true? (j/get attr-schema :unique?)))
(defn ref? [attr-schema] (true? (j/get attr-schema :ref?)))

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

;; indexing

;; when the schema is normalized - we make lists of per-value and per-datom
;; indexers - which all datoms are looped through.
(defn ave-indexer [schema]
  (when true #_(indexed? schema)
    {:name :ave
     :per :value
     :f (fn [db e a v pv ^boolean some-v? ^boolean some-pv?]
          (cond-> db
                  some-v?
                  (update-in [:ave a v] (if (unique? schema)
                                          (set-unique a v)
                                          conj-set) e)
                  some-pv?
                  (disj-in [:ave a pv] e)))}))

(defn vae-indexer [schema]
  (when true #_(ref? schema)
    {:name :vae
     :per :value
     :f (fn [db e a v pv ^boolean some-v? ^boolean some-pv?]
          (cond-> db
                  some-v?
                  (update-in [:vae v a] conj-set e)
                  some-pv?
                  (disj-in [:vae pv a] e)))}))

(defn a-indexer [schema]
  (when (_a_? schema)
    {:name :_a_
     :per :datom
     :f (fn [db e a v pv ^boolean some-v? _]
          (update-in db [:_a_ a] (if some-v? conj-set disj) e))}))

(defn make-indexer [schema]
  ;; one indexer per attribute
  (let [[per-values
         per-datoms] (->> [ave-indexer
                           vae-indexer
                           a-indexer]
                          (keep #(% schema))
                          (reduce (fn [out {:as i :keys [per f]}]
                                    (case per
                                      :value (update out 0 conj f)
                                      :datom (update out 1 conj f)))
                                  [[] []]))
        is-many? (many? schema)]
    (when (or (seq per-values) (seq per-datoms))
      (let [per-values (fast/comp6 per-values)
            per-datoms (fast/comp6 per-datoms)]
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
                            db))))))))))

(defn- update-indexes [db datom schema]
  (if-some [index (j/get schema :index-fn)]
    (index db datom)
    db))

;; transactions

(defn conj-datom [datoms datom] (cond-> datoms *report-datoms?* (conj! datom)))

(defn- clear-empty-ent [db e]
  (cond-> db
          (empty? (fast/get-in db [:eav e]))
          (update :eav dissoc e)))

(defn- retract-attr
  [[db datoms :as state] [_ e a v]]
  (if-some [pv (fast/get-in db [:eav e a])]
    (let [attr-schema (get-schema db a)]
      (if (many? attr-schema)
        (if-some [removals (guard (set/intersection v pv) seq)]
          (let [datom [e a nil removals]]
            [(-> (difference-in db [:eav e a] removals)
                 (update-indexes datom attr-schema))
             (conj-datom datoms datom)])
          state)
        (let [datom [e a nil pv]]
          [(-> (update-in db [:eav e] dissoc a)
               (update-indexes datom attr-schema)
               (clear-empty-ent e))
           (conj-datom datoms datom)])))
    state))

;; retracts each attribute in the entity
(defn- retract-entity [state [_ e]]
  (reduce-kv (fn [state a v]
               (retract-attr state [nil e a v]))
             state
             (get-entity (state 0) e)))

(defn- add
  [[db datoms :as state] [_ e a v]]
  (let [attr-schema (get-schema db a)
        db (if (contains? (:eav db) e) db (assoc-in db [:eav e] {:db/id e}))
        pv (fast/get-in db [:eav e a])
        v (cond-> v (ref? attr-schema) (resolve-e db))]
    (if (many? attr-schema)
      (if-some [additions (guard (set/difference v pv) seq)]
        (let [datom [e a additions nil]]
          [(-> (assoc-in db [:eav e a] (into-set pv additions))
               (update-indexes datom attr-schema))
           (conj-datom datoms datom)])
        state)
      (if (= pv v)
        state
        (let [datom [e a v pv]]
          [(-> (assoc-in db [:eav e a] v)
               (update-indexes datom attr-schema))
           (conj-datom datoms datom)])))))

(defn commit-datom [[db datoms] [e a v pv :as datom]]
  (let [attr-schema (get-schema db a)]
    [(-> (if (many? attr-schema)
           ;; for cardinality/many, `v` is a set of "additions" and `pv` is a set of "removals"
           (update-set db [:eav e a] (fn [dbv]
                                       (-> (into (or dbv #{}) v)
                                           (set/difference pv))))
           (if (some? v)
             (assoc-in db [:eav e a] v)
             (dissoc-in db [:eav e a])))
         (update-indexes datom attr-schema))
     (conj! datoms datom)]))

(defn reverse-datom [datom]
  (-> datom
      ;; switch `v` and `pv`
      (assoc 2 (datom 3)
             3 (datom 2))))

;; generate internal db uuids
(defonce last-e (volatile! 0))
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
      (assert (not (false? e-from-attr)) "must have a unique attribute")
      (assoc m :db/id (or e-from-attr (gen-e))))))

(defn- add-attr-index [[db datoms m :as state] e a pv a-schema]
  (let [is-many (many? a-schema)]
    (if-some [datom (let [v (get m a)]
                      (if is-many
                        (let [v (set/difference v pv)
                              pv (set/difference pv v)]
                          (when (or (seq v) (seq pv))
                            [e a v pv]))
                        (when (not= v pv)
                          [e a v pv])))]
      [(update-indexes db datom a-schema)
       (conj-datom datoms datom)
       m]
      state)))

(declare add-map)

(defn resolve-attr-refs [[db datoms m :as state] a v a-schema]
  (let [[db datoms nv]
        (if (many? a-schema)
          (reduce
           (fn [[db datoms vs :as state] v]
             (cond (vector? v)
                   [db datoms (-> vs
                                  (disj v)
                                  (conj (or (resolve-e v db) v)))]
                   (map? v)
                   (let [sub-entity (resolve-map-e db v)]
                     (conj
                      (add-map [db datoms] sub-entity)
                      (-> vs (disj v) (conj (:db/id sub-entity)))))
                   :else state))
           [db datoms v]
           v)
          (cond (vector? v)
                [db datoms (or (resolve-e v db) v)]
                (map? v)
                (let [sub-entity (resolve-map-e db v)]
                  (conj (add-map [db datoms] sub-entity)
                        (:db/id sub-entity)))
                :else [db datoms v]))]
    (if (identical? v nv)
      state
      [db datoms (assoc m a nv)])))

(defn- add-map
  [[db datoms] m]
  (let [{:as m e :db/id} (resolve-map-e db m)]
    (let [pm (get-entity db e)
          db-schema (:schema db)
          [db datoms m] (reduce-kv (fn [state a v]
                                     (let [a-schema (db-schema a)]
                                       (-> state
                                           (cond-> (ref? a-schema)
                                                   (resolve-attr-refs a v a-schema))
                                           (add-attr-index e a (get pm a) a-schema))))
                                   [db datoms m]
                                   m)]
      [(assoc-in db [:eav e] m) datoms])))

(defn- commit-tx [state tx]
  (if (vector? tx)
    (case (tx 0)
      :db/add (add state (update tx 1 resolve-e (state 0)))
      :db/retractEntity (retract-entity state (update tx 1 resolve-e (state 0)))
      :db/retract (retract-attr state (update tx 1 resolve-e (state 0)))
      :db/datoms (reduce commit-datom state (tx 1))
      :db/datoms-reverse (->> (tx 1)
                              (reverse)
                              (map reverse-datom)
                              (reduce commit-datom state))
      (throw (js/Error (str "No db op: " (tx 0)))))
    (add-map state (update tx :db/id resolve-e (state 0)))))

(defn- transaction [db-before new-txs]
  (let [[db-after datoms] (reduce commit-tx [db-before (transient [])] new-txs)]
    {:db-before db-before
     :db-after db-after
     :datoms (persistent! datoms)}))

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
   (binding [*report-datoms?* (boolean (or notify-listeners? report-datoms?))]
     (when-let [{:keys [db-after datoms] :as tx} (cond (nil? txs) nil
                                                       (:datoms txs) txs
                                                       (sequential? txs) (transaction @conn txs)
                                                       :else (throw (js/Error "Transact! was not passed a valid transaction")))]
       (reset! conn db-after)
       (when (and notify-listeners? (not *prevent-notify*))
         (doseq [f (vals (:listeners db-after))]
           (f conn tx)))
       tx))))

(def many {:db/cardinality :db.cardinality/many})
(def ref {:db/valueType :db.type/ref})
(def unique-id {:db/unique :db.unique/identity})
(def unique-value {:db/unique :db.unique/value})
(def indexed {:db/index true})

(defn compile-a-schema [db-schema a {:as a-schema
                                     :db/keys [index unique cardinality valueType]}]
  (if (= a :db/uniques)
    db-schema
    ;; copy schema values to javascript properties for fast access
    (let [unique? (boolean unique)
          a-schema (j/assoc! a-schema
                             :indexed? (boolean (or index unique?))
                             :many? (= cardinality :db.cardinality/many)
                             :unique? unique?
                             :ref? (= valueType :db.type/ref)
                             :_a_? (:db.index/_a_ a-schema))
          a-schema (j/assoc! a-schema :index-fn (make-indexer a-schema))]
      (-> (assoc db-schema a a-schema)
          (update :db/uniques (if unique? (fnil conj #{}) disj) a)))))

(defn compile-db-schema [schema]
  (->> (assoc schema :db/ident {:db/unique :db.unique/identity})
       (reduce-kv compile-a-schema {})))

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
   (atom {:schema (compile-db-schema schema)})))
