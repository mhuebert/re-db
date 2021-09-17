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
(defn ref? [attr-schema] (true? (j/get attr-schema :vae?)))

(defn resolve-e
  "Returns entity id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [e db]
  (if (vector? e)
    (first (fast/get-in db [:ave (e 0) (e 1)]))
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

(defn- update-index [db e a v pv ^boolean is-ref ^boolean is-unique ^boolean is-indexed]
  (let [;; add
        db (if (some? v)
             (cond-> db
                     is-indexed (update-in [:ave a v] (if is-unique
                                                        (set-unique a v)
                                                        conj-set) e)
                     is-ref (update-in [:vae v a] conj-set e))
             db)
        ;; remove
        db (if (some? pv)
             (cond-> db
                     is-indexed (disj-in [:ave a pv] e)
                     is-ref (disj-in [:vae pv a] e))
             db)]
    db))

(defn compindex [[f & fs]]
  (reduce (fn [f1 f2]
            (fn [db e a v pv]
              (f2 (f1 db e a v pv) e a v pv))) f fs))

(defn make-index-fn [schema]
  (let [is-ref (ref? schema)
        is-unique (unique? schema)
        is-many (many? schema)
        is-indexed (indexed? schema)
        _a_? (_a_? schema)]
    (compindex
     (cond-> []
             (or is-ref is-indexed)
             (conj (fn [db e a v pv]
                     (if is-many
                       ;; cardinality-many attrs must update index for each item in set
                       (as-> db db
                             ;; additions
                             (if v (reduce (fn [db v] (update-index db e a v nil is-ref is-unique is-indexed)) db v) db)
                             ;; removals
                             (if pv (reduce (fn [db pv] (update-index db e a nil pv is-ref is-unique is-indexed)) db pv) db))
                       (update-index db e a v pv is-ref is-unique is-indexed))))
             _a_?
             (conj (fn [db e a v pv]
                     (let [v? (if is-many (seq v) (some? v))]
                       (if v?
                         (update-in db [:_a_ a] conj-set e)
                         (update-in db [:_a_ a] disj e)))))))))

(defn- update-indexes [db e a v pv schema]
  (if-some [index (j/get schema :index-fn)]
    (index db e a v pv)
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
          [(-> (difference-in db [:eav e a] removals)
               (update-indexes e a nil removals attr-schema))
           (conj-datom datoms [e a nil removals])]
          state)
        [(-> (update-in db [:eav e] dissoc a)
             (update-indexes e a nil pv attr-schema)
             (clear-empty-ent e))
         (conj-datom datoms [e a nil pv])]))
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
        [(-> (assoc-in db [:eav e a] (into-set pv additions))
             (update-indexes e a additions nil attr-schema))
         (conj-datom datoms [e a additions nil])]
        state)
      (if (= pv v)
        state
        [(-> (assoc-in db [:eav e a] v)
             (update-indexes e a v pv attr-schema))
         (conj-datom datoms [e a v pv])]))))

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
         (update-indexes e a v pv attr-schema))
     (conj! datoms datom)]))

(defn reverse-datom [datom]
  (-> datom
      ;; switch `v` and `pv`
      (assoc 2 (datom 3)
             3 (datom 2))))

;; generate internal db uuids
(defonce last-e (volatile! 0))
(defn gen-e [] (str "re-db.id/" (vswap! last-e inc)))

(defn e-from-unique-attr
  "Finds an entity id by looking for a unique attribute in `m`.

   Special return values:
   nil   => a unique attribute was found, but no matching entry in the db
   false => no unique attributes found in `m`"
  [db m]
  (let [unique-attrs (-> db :schema ::unique-attrs)]
    (reduce (fn [ret a]
              (fast/if-found [v (m a)]
                (if-some [e (resolve-e [a v] db)]
                  (reduced e)
                  nil)
                ret)) false
            unique-attrs)))

;; adds :db/id to entity based on unique attributes
(defn- resolve-map-e [db m]
  (if (some? (:db/id m))
    m
    (let [e-from-attr (e-from-unique-attr db m)]
      (assert (not (false? e-from-attr)) "must have a unique attribute")
      (assoc m :db/id (or e-from-attr (gen-e))))))

;; adds indexes for map entries without creating intermediate data structures
(defn- add-map-indexes [state e prev-m]
  (reduce-kv
   (fn [state a v]
     (let [attr-schema (get-schema (state 0) a)
           pv (get prev-m a)]
       (if (= v pv)
         state
         (if (many? attr-schema)
           (let [v (guard (set/difference v pv) seq)
                 pv (guard (set/difference pv v) seq)]
             (if (or v pv)
               [(update-indexes (state 0) e a v pv attr-schema)
                (conj-datom (state 1) [e a v pv])]
               state))
           [(update-indexes (state 0) e a v pv attr-schema)
            (conj-datom (state 1) [e a v pv])]))))
   state
   (get-entity (state 0) e)))

(declare add-map)

;; handle upserts & entity-id resolution
(defn- resolve-map-refs
  [[{:as db db-schema :schema} datoms] e]
  (let [m (get-entity db e)
        [db datoms m] (reduce-kv (fn [[db datoms m :as ret] a v]
                                   (if-some [attr-schema (guard (db-schema a) ref?)]
                                     (if (many? attr-schema)
                                       (reduce
                                        (fn [[db datoms entity :as ret] val]
                                          (cond (vector? val)
                                                [db datoms (update entity a #(-> % (disj val) (conj (or (resolve-e val db) val))))]
                                                (map? val)
                                                (let [sub-entity (resolve-map-e db val)]
                                                  (conj (add-map [db datoms] sub-entity)
                                                        (update entity a #(-> % (disj val) (conj (:db/id sub-entity))))))
                                                :else ret))
                                        [db datoms m]
                                        v)
                                       (cond (vector? v)
                                             [db datoms (assoc m a (or (resolve-e v db) v))]
                                             (map? v)
                                             (let [sub-entity (resolve-map-e db v)]
                                               (conj (add-map [db datoms] sub-entity)
                                                     (assoc m a (:db/id sub-entity))))
                                             :else ret))
                                     ret))
                                 [db datoms m]
                                 m)]
    [(assoc-in db [:eav (:db/id m)] m) datoms m]))

(defn- add-map
  [[db datoms :as state] m]
  (let [{:as m e :db/id} (resolve-map-e db m)]
    (-> [(assoc-in db [:eav e] m) datoms]
        (resolve-map-refs e)
        (add-map-indexes e (get-entity db e)))))

(defn- commit-tx [state tx]
  (if (vector? tx)
    (case (tx 0)
      :db/add (add state (update tx 1 resolve-e (state 0)))
      :db/retract-entity (retract-entity state (update tx 1 resolve-e (state 0)))
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

(defn unique-id
  "Returns a unique id (string)."
  []
  (str (uuid-utils/make-random-uuid)))

(defn normalize-schema [schema]
  (let [schema (assoc schema :db/ident {:db/unique :db.unique/identity})]
    (reduce-kv (fn [db-schema attr {:as attr-schema
                                    :db/keys [index unique cardinality valueType]}]
                 ;; copy schema values to javascript properties for fast access
                 (let [unique? (= unique :db.unique/identity)
                       attr-schema (j/assoc! attr-schema
                                             :indexed? (boolean (or index unique?))
                                             :many? (= cardinality :db.cardinality/many)
                                             :unique? (boolean unique?)
                                             :vae? (= valueType :db.type/ref)
                                             :_a_? (:db.index/_a_ attr-schema))
                       attr-schema (j/assoc! attr-schema :index-fn (make-index-fn attr-schema))]
                   (-> db-schema
                       (assoc attr attr-schema)
                       (cond-> unique?
                               (update ::unique-attrs (fnil conj #{}) attr)))))
               schema schema)))

(defn merge-schema!
  "Merge additional schema options into a db. Indexes are not created for existing data."
  [db schema]
  (swap! db update :schema (comp normalize-schema (partial merge-with merge)) schema))

(defn create-conn
  "Create a new db, with optional schema, which should be a mapping of attribute keys to
  the following options:

    :db/index       [true, :db.index/unique]
    :db/cardinality [:db.cardinality/many]"
  ([] (create-conn {}))
  ([schema]
   (atom {:schema (normalize-schema schema)})))
