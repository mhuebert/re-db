(ns re-db.core
  (:refer-clojure :exclude [indexed? ref clone])
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [re-db.fast :as fast]
            [re-db.history :as history]
            [re-db.util :as util :refer [guard set-replace]]
            [re-db.schema :as schema]))

(def index-all-ave? false)
(def index-all-ae? false)

(defrecord Schema [^boolean ave ^boolean many ^boolean unique ^boolean ref ^boolean ae index-fn])

(#?(:cljs js/console.info
    :clj  prn) "re-db/index-all:"
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
  (let [is-unique (:unique schema)]
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
          (ae-indexer* db e a v pv v? pv? (:many (get-schema db a))))
        (let [is-many ^boolean (:many a-schema)]
          (fn [db e a v pv v? pv?]
            (ae-indexer* db e a v pv v? pv? is-many))))})

(def make-indexer
  (memoize
   (fn [^Schema a-schema]
     ;; one indexer per attribute
     (let [[per-value per-datom] (->> (cond-> []
                                              (:ave a-schema) (conj (ave-indexer a-schema))
                                              (:ref a-schema) (conj (vae-indexer a-schema))
                                              (:ae a-schema) (conj (ae-indexer a-schema)))
                                      (reduce (fn [out {:keys [per f]}]
                                                (case per
                                                  :value (update out 0 conj f)
                                                  :datom (update out 1 conj f)))
                                              [[] []]))
           is-many? (:many a-schema)]
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

(defn compile-a-schema* ^Schema [{:db/keys [index unique cardinality valueType index-ae]}]
  (let [unique? (boolean unique)
        index (or index-all-ave? index)
        ae (boolean (or index-all-ae? index-ae))
        a-schema (map->Schema
                  {:ave (boolean (or index unique?))
                   :many (= cardinality :db.cardinality/many)
                   :unique unique?
                   :ref (= valueType :db.type/ref)
                   :ae ae})]
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
      (if ^boolean (:many a-schema)
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
        v (cond-> v ^boolean (:ref a-schema) (resolve-e db))]
    (if ^boolean (:many a-schema)
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
    (-> (if ^boolean (:many a-schema)
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
  (if (some? (:db/id m))
    m
    (let [e-from-attr (e-from-unique-attr db m)]
      (assert (not (false? e-from-attr)) (str "must have a unique attribute" m))
      (assoc m :db/id (or e-from-attr (gen-e))))))

(defn set-diff [s1 s2]
  (cond (identical? s1 s2) nil
        (nil? s1) [s2 nil]
        (nil? s2) [nil s1]
        :else (let [added (set/difference s2 s1)
                    removed (set/difference s1 s2)]
                (when (or (seq added) (seq removed))
                  [added removed]))))

(defn- add-attr-index [[db m :as state] e a pv ^Schema a-schema]
  (let [v (m a)
        is-many ^boolean (:many a-schema)]
    (if-some [[v pv] (if is-many
                       (set-diff pv v)
                       (when (not= v pv)
                         [v pv]))]
      [(index db a-schema e a v pv) m]
      state)))

(declare add-map)

(defn handle-lookup-ref [db v]
  (if (vector? v)
    (or (resolve-e v db)
        {(v 0) (v 1)})
    v))

(defn resolve-attr-refs [[db m :as state] a v ^Schema a-schema]
  (let [[db newv]
        (if ^boolean (:many a-schema)
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
              [db v-resolved])))]
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
                                    (cond-> ^boolean (:ref a-schema)
                                            (resolve-attr-refs a v a-schema))
                                    (add-attr-index e a (get pm a) a-schema))))
                            [db m]
                            m)]
      (fast/update! db :eav assoc! e (cond->> m pm (merge pm))))))

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
    (add-map db (update tx :db/id resolve-e db))))


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

(defn noop-report [db-before]
  (history/handle-tx-report {:db-before db-before :db-after db-before :datoms []} {}))

(defn- transaction
  ([db-before {:keys [txs options]}] (transaction db-before txs options))
  ([db-before txs options]
   {:pre [db-before (or (nil? txs) (sequential? txs))]}
   (if (history/commit? db-before options)
     (binding [*datoms* (when (:notify-listeners? options true) #?(:cljs #js[] :clj (volatile! [])))]
       (let [db-after (persistent-map! (reduce commit-tx (transient-map (history/without-history db-before)) txs))
             datoms (if *datoms*
                      #?(:cljs (vec *datoms*)
                         :clj  @*datoms*)
                      [])]
         (if (seq datoms)
           (-> options
               (assoc :db-before db-before
                      :db-after db-after
                      :datoms datoms)
               (history/handle-tx-report options))
           (noop-report db-before))))
     (noop-report db-before))))

(defn commit!
  ;; separating out the "commit" step because we may extend `transaction` to support
  ;; transaction functions and arbitrary effects
  "Commits a tx-report to !conn"
  ([!conn tx-report] (commit! !conn tx-report {}))
  ([!conn tx-report options]
   (when (seq (:datoms tx-report))
     (if (history/commit? @!conn tx-report)
       (do

         (reset! !conn (:db-after tx-report))

         (when *branch-tx-log*
           (swap! *branch-tx-log* conj tx-report))

         (when (and (:notify-listeners? options true)
                    (not *prevent-notify*))
           (doseq [f (-> tx-report :db-after :listeners vals)]
             (f !conn tx-report)))
         tx-report)
       (prn (str "Ignoring transaction - db is frozen in the past"))))))

(defn transact!
  "Transacts txs and returns a tx-report"
  ([conn txs] (transact! conn txs {}))
  ([conn txs opts]
   (commit! conn (transaction @conn txs opts) opts)))

(defn db-as-of
  "Returns db as of `tx` in history"
  [db tx]
  (:db-after (transaction db (history/travel-tx db tx))))

(defn compile-a-schema [db-schemas a a-schema]
  (if (or (= :db/ident a) (not= "db" (namespace a)))
    (let [a-schema (compile-a-schema* a-schema)]
      (-> (assoc db-schemas a a-schema)
          (update :db/uniques (if ^boolean (:unique a-schema) (fnil conj #{}) disj) a)))
    (assoc db-schemas a a-schema)))

(defn compile-db-schema [schema]
  (->> (assoc schema :db/ident {:db/unique :db.unique/identity})
       (reduce-kv compile-a-schema {})))

(defn add-missing-index [db a indexk]
  (let [{:as db
         :keys [schema]} (update db :schema
                                 (fn [db-schema]
                                   (compile-a-schema db-schema a
                                                     (merge (get db-schema a)
                                                            (case indexk
                                                              :ae schema/ae
                                                              :ave schema/ave)))))
        a-schema ^Schema (schema a)
        many? ^boolean (:many a-schema)
        {:keys [f per]} ((case indexk :ae ae-indexer :ave ave-indexer) a-schema)]
    (-> (reduce-kv (if (and many? (= per :value))
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
  "Merge additional schema options into a db. Indexes are not created for existing data."
  [db schema]
  (swap! db update :schema (comp compile-db-schema (partial merge-with merge)) schema))

(def core-ks [:ae :eav :vae :ave :schema])
(def history-ks (keys (history/init-history {})))
(def all-ks (into core-ks history-ks))

(defn create-conn
  "Create a new db, with optional schema, which should be a mapping of attribute keys to
  the following options:

    :db/index       [true, :db.index/unique]
    :db/cardinality [:db.cardinality/many]"
  ([] (create-conn {}))
  ([schema]
   (atom (-> {:ae {}
              :eav {}
              :vae {}
              :ave {}
              :schema (compile-db-schema schema)}
             history/init-history))))

(defn clone [!conn]
  (atom (select-keys @!conn all-ks)))

(defn travel!
  "Moves !conn to `tx` in history"
  [!conn tx]
  (let [db @!conn]
    (doto !conn (commit! (transaction db (history/travel-tx db tx))))))

(defn as-of
  "Returns new !conn with `tx` set"
  [!conn tx]
  (travel! (clone !conn) tx))