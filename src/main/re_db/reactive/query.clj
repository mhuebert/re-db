(ns re-db.reactive.query
  (:require [datomic.api :as d]
            [re-db.fast :as fast]
            [re-db.reactive :as r]
            [re-db.util :as util :refer [defprotocol-once some-or]]
            [re-db.subscriptions :as subs]))

;; modified from https://github.com/clojure/core.incubator/blob/4f31a7e176fcf4cc2be65589be113fc082243f5b/src/main/clojure/clojure/core/incubator.clj#L63
(defn disj-in
  "Removes value from set at path. Any empty sets or maps that result
  will not be present in the new structure."
  [m [k & ks :as path] v]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (disj-in nextmap ks v)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (let [new-set (disj (get m k) v)]
      (if (seq new-set)
        (assoc m k new-set)
        (dissoc m k)))))

;; reading from the schema

(defn ref?
  ([a-entity] (= :db.type/ref (:db/valueType a-entity)))
  ([db a] (ref? (d/entity db a))))

(defn many?
  ([a-entity] (= :db.cardinality/many (:db/cardinality a-entity)))
  ([db a] (many? (d/entity db a))))

;; dynamic vars for *patterns* and *db*. Dynamic vars are appropriate here because
;; all reactive reads must occur in the context of an "owner" resource, which
;; becomes dependent on the logged patterns.

(def ^:dynamic *db* nil)

(defonce !state (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Patterns - for granular dependencies on datom patterns

(def ^:dynamic *patterns* nil)

(defprotocol-once IPatterns
  (conn [this])
  (get-patterns [this])
  (set-patterns! [this new-patterns]))

(def empty-patterns [])

(defn handle-new-patterns! [conn consumer new-patterns]
  ;; update pattern-registry with any changes to this query's dependencies
  (let [patterns (get-patterns consumer)
        [added removed] (util/set-diff patterns new-patterns)]

    (swap! !state update-in [conn :pattern-listeners]
           (fn [listeners]
             (as-> listeners listeners
                   (reduce (fn [listeners pattern] (update-in listeners pattern (fnil conj #{}) consumer)) listeners added)
                   (reduce (fn [listeners pattern] (disj-in listeners pattern consumer)) listeners removed)))))
  (set-patterns! consumer new-patterns))

(defn dispose-patterns! [this]
  (handle-new-patterns! (conn this) this empty-patterns))

(defmacro capture-patterns
  "Evaluates body with reads from `db` logged."
  [db & body]
  `(binding [*patterns* (volatile! #{})
             *db* ~db]
     (let [value# (do ~@body)]
       [value# @*patterns*])))

(defmacro track-patterns!
  "Evaluates `body`, logging patterns and registering `consumer` to be invalidated when
   matching datoms are transacted to `conn`."
  [conn & body]
  `(let [[value# new-patterns#] (capture-patterns (d/db ~conn) ~@body)]
     (handle-new-patterns! ~conn r/*owner* new-patterns#)
     value#))

(defmacro once
  "Evaluates body with `db` bound, but without logging patterns (non-reactive)"
  [db & body]
  `(binding [*db* ~db] ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolving datom components.
;; we need to "resolve" the contents of our patterns so that we can
;; match them with data in the tx-log (which contains internal db identifiers)

(declare log-pattern!)

(defn resolve-lookup-ref [db [a v :as e]]
  (if (vector? v) ;; nested lookup ref
    (resolve-lookup-ref db [a (resolve-lookup-ref db v)])
    (when v
      (log-pattern! db nil a v)
      (:db/id (d/entity db e)))))

(defn resolve-e [conn e]
  (if (vector? e)
    (resolve-lookup-ref conn e)
    (:db/id e e)))

(defn resolve-a [db a] (:db/id (d/entity db a)))
(defn resolve-v [db a v] (when v (cond->> v (ref? db a) (resolve-e db))))

(defn resolve-pattern [db e a v]
  [(when e (resolve-e db e))
   (when a (resolve-a db a))
   (when v (resolve-v db a v))])

(defn log-pattern!
  "Adds e-a-v pattern to the current *patterns*. Used to track dependencies."
  [db e a v]
  (when *patterns*
    (let [p (resolve-pattern db e a v)]
      (vswap! *patterns* conj p)
      p)))

;; logged reads

(defn ea_ [e a]
  (log-pattern! *db* e a nil)
  (get (d/entity *db* e) a))

(defn _a_ [a]
  (log-pattern! *db* nil a nil)
  (d/q
   '[:find [?e ...]
     :where [?e ?a]
     :in $ ?a]
   *db* a))

(defn av_
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [a v]
  (when-let [v (resolve-v *db* a v)]
    (log-pattern! *db* nil a v)
    (d/q
     '[:find [?e ...]
       :where [?e ?a ?v]
       :in $ ?a ?v]
     *db* a v)))


(declare entity)

(deftype Entity [db e ^:volatile-mutable db-entity]
  clojure.lang.ILookup
  (valAt [o a]
    (let [db-entity (or db-entity
                        (when-let [dbe (d/entity db e)]
                          (set! db-entity dbe)
                          dbe))]
      (case a
        :db/id e
        (let [v (get db-entity a)
              is-reverse (util/reverse-attr? a)
              k (cond-> a is-reverse util/forward-attr)
              attribute-e (d/entity db k)]
          (if is-reverse
            (do
              (log-pattern! db nil k e) ;; [_ a v]
              (into #{} (map #(entity db %)) v))
            (do
              (log-pattern! db e k nil) ;; [e a _]
              (if (ref? attribute-e)
                (if (many? attribute-e)
                  (into #{} (map #(entity db %)) v)
                  (entity db v))
                v)))))))
  (valAt [o k nf]
   ;; there is no real "not-found" semantic in datomic, nil is equivalent to not-present
    (if-some [v (get o k)] v nf))
  clojure.lang.IDeref
  (deref [this]
    (log-pattern! db e nil nil)
    (when-let [db-entity (or db-entity
                             (when-let [dbe (d/entity db e)]
                               (set! db-entity dbe)
                               dbe))]
      (d/touch db-entity))))

(defn entity
  ([db e]
   (let [e (:db/id e e)]
     (->Entity db e (d/entity db e))))
  ([e] (entity *db* e)))

(comment
 '{conn {:queries {} ;; cache of active queries
         :stop ƒ ;; stops the tx-listener
         :pattern-listeners {} ;; map of {e {a {v #{...queries}}}

         }})

(defn dispose-conn [state conn]
  (when-let [stop (get-in state [conn :stop])]
    (stop))
  (dissoc state conn))

;; clear state when reloading namespace
#_(swap! !state (fn [state] (reduce dispose-conn state (keys state))))

(deftype Query [conn ^:volatile-mutable f ratom ^:volatile-mutable patterns ^:volatile-mutable derefs ^:volatile-mutable hooks]
  r/IHook
  (get-hooks [this] hooks)
  (set-hooks! [this new-hooks] (set! hooks new-hooks))
  clojure.lang.IDeref
  (deref [this]
    (assert (not= ::disposed (r/peek ratom)) "Cannot deref a disposed query")
    @ratom)
  IPatterns
  (conn [this] conn)
  (get-patterns [this] patterns)
  (set-patterns! [this new-patterns] (set! patterns new-patterns))
  r/ICaptureDerefs
  (get-derefs [this] derefs)
  (set-derefs! [this new-derefs] (set! derefs new-derefs))
  r/ICompute
  (-set-function! [this new-f] (set! f new-f))
  (invalidate! [this]
    (let [;; recompute value of query, recording patterns
          new-value (try {:value (r/with-owner this
                                   (r/capture-derefs!
                                    (r/support-hooks!
                                     (track-patterns! conn
                                       (f)))))}
                         (catch Exception e
                           (prn :error-invalidating-query (ex-message e))
                           {:error (ex-message e)}))
          changed? (not= new-value (r/peek ratom))]
      (when changed?
        (reset! ratom new-value))
      (r/peek ratom)))
  r/IDispose
  (get-dispose-fns [this] (r/get-dispose-fns ratom))
  (set-dispose-fns! [this dispose-fns] (r/set-dispose-fns! ratom dispose-fns))

  ;; clients should be added using add-watch, and removed upon disconnection.
  clojure.lang.IRef
  (addWatch [this key watch-fn] (add-watch ratom key watch-fn) this)
  (removeWatch [this key] (remove-watch ratom key) this))

(defn handle-datoms!
  ;; given a re-db transaction, invalidates readers based on
  ;; patterns found in transacted datoms.
  [pattern-listeners datoms]
  (when pattern-listeners
    (let [queries (let [result (volatile! #{})
                        collect! #(vswap! result into %)]
                    (doseq [[e a v] datoms]
                      ;; triggers patterns for datom, with "early termination"
                      ;; for paths that don't lead to any invalidators.
                      (when-some [e (pattern-listeners e)]
                        (collect! (fast/gets-some e nil nil)) ;; e__
                        (collect! (fast/gets-some e a nil))) ;; ea_
                      (when-some [_ (pattern-listeners nil)]
                        (when-some [__ (_ nil)]
                          (collect! (__ v))) ;; __v (used for refs)
                        (when-some [_a (_ a)]
                          (collect! (_a v)) ;; _av
                          (collect! (_a nil))))) ;; _a_
                    @result)]
      (doseq [query queries] (r/invalidate! query)))))

(defn make-query
  [conn f]
  (let [state (r/atom nil)
        query (->Query conn
                       f
                       (r/atom nil)
                       empty-patterns
                       r/empty-derefs
                       r/empty-hooks)]
    (r/add-on-dispose! state (fn []
                               (dispose-patterns! query)
                               (r/dispose-derefs! query))))
  (doto (->Query conn
                 f
                 (r/atom nil)
                 empty-patterns
                 r/empty-derefs
                 r/empty-hooks)
    r/invalidate!))

(defn find-or-create-query! [f conn & args]
  (let [qvec [f args]]
    (or (get-in @!state [conn :queries qvec])
        (let [the-query (make-query conn (if (var? f)
                                           #(apply @f conn args)
                                           #(apply f conn args)))]
          (r/add-on-dispose! the-query (fn [_] (swap! !state update-in [conn :queries] dissoc qvec)))
          (swap! !state assoc-in [conn :queries qvec] the-query)
          the-query))))

(defn apply-f-to-entities [f entities]
  (if (set? entities)
    (into #{} (map f) entities)
    (f entities)))

(defn- pull*
  ([entity pullv] (pull* entity pullv #{}))
  ([{:as ^Entity the-entity} pullv found]
   (when the-entity
     (reduce-kv
      (fn pull [m i pullexpr]
        (if (= '* pullexpr)
          (merge m @the-entity)
          (let [[a map-expr] (if (or (keyword? pullexpr) (list? pullexpr))
                               [pullexpr nil]
                               (first pullexpr))
                [a alias val-fn] (if (list? a)
                                   (let [{:as opts
                                          :keys [default limit]
                                          alias :as
                                          :or {alias a}} (apply hash-map (rest a))
                                         a (first a)]
                                     (if (:db/id opts)
                                       [a :db/id #(vector a %)]
                                       [a alias (comp #(if limit (take limit %) %)
                                                      (or (when default #(some-or % default))
                                                          identity))]))
                                   [a a identity])
                forward-a (cond-> a (util/reverse-attr? a) util/forward-attr)
                a-schema (d/entity (.db the-entity) forward-a)
                v (val-fn (get the-entity a))]
            (assoc m alias
                     (cond (not (ref? a-schema)) v

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
                                                               (some-> (entity (.db the-entity) %)
                                                                       (pull* pullv found))))
                                                      v))
                                              v))]
                             (cond-> entities (not (many? a-schema)) first))

                           ;; cardinality/many
                           (many? a-schema) (into #{} (map #(pull* % map-expr)) v)

                           ;; cardinality/one
                           :else (pull* v map-expr))))))
      {}
      pullv))))

(def ^:dynamic *pull-flat* nil)
(defn pull-flat
  ([entity pullv] (binding [*pull-flat* (volatile! [])]
                    (pull-flat entity pullv #{})
                    @*pull-flat*))
  ([{:as ^Entity the-entity} pullv found]
   (when the-entity
     (let [collect! (fn [x]
                      (do (vswap! *pull-flat* conj x) (:db/id x)))]
       (collect! (reduce-kv
                  (fn pull [m i pullexpr]
                    (if (= '* pullexpr)
                      (merge m (-> @the-entity (update-vals collect!)))
                      (let [[a map-expr] (if (or (keyword? pullexpr) (list? pullexpr))
                                           [pullexpr nil]
                                           (first pullexpr))
                            [a alias val-fn] (if (list? a)
                                               (let [{:as opts
                                                      :keys [default limit]
                                                      alias :as
                                                      :or {alias a}} (apply hash-map (rest a))
                                                     a (first a)]
                                                 (if (:db/id opts)
                                                   [a :db/id #(vector a %)]
                                                   [a alias (comp #(if limit (take limit %) %)
                                                                  (or (when default #(some-or % default))
                                                                      identity))]))
                                               [a a identity])
                            forward-a (cond-> a (util/reverse-attr? a) util/forward-attr)
                            a-schema (d/entity (.db the-entity) forward-a)
                            v (val-fn (get the-entity a))]
                        (assoc m alias
                                 (cond (not (ref? a-schema)) v

                                       ;; ref without pull-expr
                                       (nil? map-expr) v ;; ? leave this as a map or return ids?

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
                                                                           (some-> (entity (.db the-entity) %)
                                                                                   (pull-flat pullv found))))
                                                                  v))
                                                          v))]
                                         (cond-> entities (not (many? a-schema)) first))

                                       ;; cardinality/many
                                       (many? a-schema) (into #{} (map (comp collect! #(pull-flat % map-expr))) v)

                                       ;; cardinality/one
                                       :else (collect! (pull-flat v map-expr)))))))
                  {}
                  pullv))))))

(defn pull
  "Returns entity as map, as well as linked entities specified in `pull`.

  (pull conn 1 [:children]) =>
    {:db/id 1
     :children [{:db/id 2}
                {:db/id 3}]}"
  ;; difference from clojure:
  ;; - if an attribute is not present, `nil` is provided
  ([pull-expr] (fn [entity] (pull entity pull-expr)))
  ([entity pull-expr]
   (if (sequential? entity)
     (into (empty entity)
           (map #(pull* % pull-expr))
           entity)
     (pull* entity pull-expr))))

;; maybe - wrap these in queries
(defn query:_a_ [conn a])

(defn clause-filter [clause]
  (if (or (fn? clause) (keyword? clause))
    (fn [entity] (clause entity))
    (let [[a v] clause]
      (cond
        ;; arbitrary function
        (fn? v) (fn [entity] (v (get entity a)))
        ;; refs - resolve `v` lookup-refs & compare to :db/id of the thing
        (ref? *db* a) (fn [entity] (= (resolve-v *db* a v) (:db/id (get entity a))))
        :else (fn [entity] (= (get entity a) v))))))

(defn where [clause & clauses]
  ;; first clause reads from db
  (let [[a v] (if (keyword? clause) [clause nil] clause)]
    (->> (cond (nil? v) (_a_ a)
               (fn? v) (filter v (_a_ a))
               :else (av_ a v))
         (into []
               (comp (map entity)
                     ;; additional clauses filter entities from step 1
                     (filter (if (seq clauses)
                               (apply every-pred (map clause-filter clauses))
                               identity)))))))

(defn register
  "Defines a reactive query function which tracks read-patterns (e-a-v) and recomputes when
   matching datoms are transacted to a Datomic connection (passed to the subscription/sub
   functions below)."
  [id f]
  (subs/register id
    (fn [conn args]
      (make-query conn #(apply f args)))
    (fn [conn args]
      #(apply f args))))

(defn query [conn qvec]
  (subs/subscription [(first qvec) conn (rest qvec)]))