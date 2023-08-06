(ns re-db.read
  "Reactive API for re-db (with explicit connection argument)."
  (:refer-clojure :exclude [get])
  (:require [re-db.fast :as fast]
            [re-db.reactive :as r]
            [re-db.triplestore :as ts]
            [re-db.util :as u])
  #?(:cljs (:require-macros [re-db.read])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Listeners
;;
;; A reactive signal per [e a v] pattern which invalidates listeners

(defonce !listeners (atom {}))

(defn- make-listener! [conn e a v]
  (let [listener (r/atom 0
                         :meta {:pattern [e a v]            ;; for debugging
                                :dispose-delay 1000}
                         :on-dispose (fn [_] (swap! !listeners u/dissoc-in [conn e a v])))]
    (swap! !listeners assoc-in [conn e a v] listener)
    listener))

(defn captured-patterns []
  (->> @r/*captured-derefs*
       (keep (comp :pattern meta))))

(defn handle-report!
  "Invalidate readers for a tx-report from conn based on datoms transacted"
  [conn tx-report]
  (when tx-report
    (if-let [listeners (@!listeners conn)]
      (let [result (volatile! #{})
            collect! #(some->> % (vswap! result conj))
            _ (ts/report-triples
               (ts/db conn)
               tx-report
               (fn [e a v]
                 ;; triggers patterns for datom, with "early termination"
                 ;; for paths that don't lead to any invalidators.
                 (when-some [e (listeners e)]
                   (collect! (fast/gets-some e nil nil))    ;; e__
                   (collect! (fast/gets-some e a nil)))     ;; ea_
                 (when-some [_ (listeners nil)]
                   (when-some [__ (_ nil)]
                     (collect! (__ v)))                     ;; __v (used for refs)
                   (when-some [_a (_ a)]
                     (collect! (_a v))                      ;; _av
                     (collect! (_a nil))))))                ;; _a_

            invalidated @result]
        #_(prn :invalidating (mapv (comp :pattern meta) invalidated))
        (doseq [!listener invalidated] (swap! !listener inc))
        (assoc tx-report ::handled (count invalidated)))
      tx-report)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare -resolve-e resolve-v)

(defn- -depend-on-triple! [conn db a-schema e a v]
  (when (and conn (or r/*captured-derefs*
                      #?(:cljs (r/get-reagent-context))))
    (let [e (-resolve-e conn db e)
          a (when a (ts/datom-a db a))
          v (if a
              (resolve-v conn db a-schema a v)
              v)]
      (r/collect-deref!                                     ;; directly create dependency
       (or (fast/gets-some @!listeners conn e a v)
           (make-listener! conn e a v)))
      nil)))

(defn- -ae [conn db a-schema a]
  (-depend-on-triple! conn db a-schema nil a nil)
  (ts/ae db a-schema a))

(defn- -resolve-lookup-ref
  ([conn db lf]
   (let [[a v] lf]
     (-resolve-lookup-ref conn db (ts/get-schema db a) a v)))
  ([conn db a-schema a v]
   (when-not (ts/unique? db a-schema)
     (throw (ex-info "Lookup ref attribute must be unique" {:lookup-ref [a v]})))
   (when (some? v)
     (if (vector? v)                                        ;; nested lookup ref
       (-resolve-lookup-ref conn db a-schema a (-resolve-lookup-ref conn db
                                                                    (ts/get-schema db (v 0))
                                                                    (v 0)
                                                                    (v 1)))
       (do
         (-depend-on-triple! conn db a-schema nil a v)
         (first (ts/ave db a-schema a v)))))))

(defn- -resolve-e [conn db e]
  (if (vector? e)
    (-resolve-lookup-ref conn db e)
    e))

(defn- resolve-v [conn db a-schema a v]
  (cond->> v
           (and v (ts/ref? db a-schema))
           (-resolve-e conn db)))

(defn- -eav
  ([conn db e]
   (when-let [id (-resolve-e conn db e)]
     (-depend-on-triple! conn db nil id nil nil)
     (ts/eav db id)))
  ([conn db e a] (-eav conn db (ts/get-schema db a) e a))
  ([conn db a-schema e a]
   (when-let [e (-resolve-e conn db e)]
     (-depend-on-triple! conn db a-schema e a nil)
     (ts/eav db a-schema e a))))

(defn- -ave [conn db a-schema a v]
  (when-let [v (resolve-v conn db a-schema a v)]
    (-depend-on-triple! conn db a-schema nil a v)
    (ts/ave db a-schema a v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity API
;;

(defonce ^:dynamic *attribute-resolvers* {})

(declare entity)

(defn- id->ident
  "Resolve ident for entity-id (numeric :db/id becomes keyword)"
  [db v]
  (cond (keyword? v) v
        (number? v) (u/guard (ts/id->ident db v) keyword?)))

(defn make-ref-wrapper [wrap-ref]
  (fn [conn e]
    (when e
      (or (id->ident (ts/db conn) e)
          (wrap-ref conn e)))))

(def !ref-wrapper-entity (delay (make-ref-wrapper entity)))
(defn root-wrapper-default [_conn m] m)

(defn get* [conn db a-schema ref-wrapper e a]
  (when e
    (if (= :db/id a)
      e
      (if-let [resolver (*attribute-resolvers* a)]
        (resolver (entity conn e))
        (let [is-reverse (u/reverse-attr? a)
              a (cond-> a is-reverse u/forward-attr)
              is-ref (ts/ref? db a-schema)
              v (if is-reverse
                  (ts/ave db a-schema a e)
                  (ts/eav db a-schema e a))]
          (if is-reverse
            (-depend-on-triple! conn db a-schema nil a e)   ;; [_ a v]
            (-depend-on-triple! conn db a-schema e a nil))  ;; [e a _]
          (if (and is-ref ref-wrapper)
            (if (or is-reverse
                    (ts/many? db a-schema))
              (mapv #(ref-wrapper conn %) v)
              (ref-wrapper conn v))
            v))))))

(defmacro -resolve-entity-e! [conn db e-sym e-resolved-sym]
  `(do (when-not ~e-resolved-sym
         (when-some [e# (-resolve-e ~conn ~db ~e-sym)]
           (set! ~e-sym e#)
           (set! ~e-resolved-sym true)))
       ~e-sym))

(defprotocol IEntity
  (conn [entity])
  (db [entity]))

(u/support-clj-protocols
  (deftype Entity [conn ^:volatile-mutable e ^:volatile-mutable e-resolved? meta]
    IEntity
    (conn [this] conn)
    (db [this] (ts/db conn))
    IMeta
    (-meta [this] meta)
    IWithMeta
    (-with-meta [this new-meta]
      (if (identical? new-meta meta)
        this
        (Entity. conn e e-resolved? new-meta)))
    IHash
    (-hash [this]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (hash [e (ts/eav db e)])))

    IEquiv
    (-equiv [this other]
      (and (instance? Entity other)
           (identical? conn (.-conn ^Entity other))
           (= (hash this) (hash other))
           (= (:db/id this) (:db/id other))))
    ILookup
    (-lookup [o a]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (get* conn db (ts/get-schema db a) @!ref-wrapper-entity e a)))
    (-lookup [o a nf]
      (case nf
        ::unwrapped
        (let [db (ts/db conn)]
          (re-db.read/-resolve-entity-e! conn db e e-resolved?)
          (get* conn db (ts/get-schema db a) nil e a))
        (if-some [v (clojure.core/get o a)] v nf)))
    IDeref
    (-deref [this]
      (let [db (ts/db conn)]
        (when-let [e (re-db.read/-resolve-entity-e! conn db e e-resolved?)]
          (-depend-on-triple! conn db nil e nil nil)
          (ts/eav db e))))
    ISeqable
    (-seq [this] (seq @this))))

(defn entity [conn e]
  (let [db (ts/db conn)
        e (or (-resolve-e conn db e) e)]
    (->Entity conn e false nil)))

(defn touch [the-entity]
  (let [m @the-entity
        conn (conn the-entity)
        db (ts/db conn)]
    (reduce-kv (fn [m a v]
                 (let [a-schema (ts/get-schema db a)]
                   (if (ts/component? db a-schema)
                     (assoc m a (if (ts/many? db a-schema)
                                  (mapv #(touch (entity conn %)) v)
                                  (touch (entity conn v))))
                     m))) m m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pull API
;;

(defn- wrap-v [v is-many f]
  (if f
    (if is-many
      (mapv f v)
      (f v))
    v))

(defn- -wrap-map-refs [ref-wrapper conn db m]
  (reduce-kv (fn [m a v]
               (let [a-schema (ts/get-schema db a)]
                 (cond-> m
                         (ts/ref? db a-schema)
                         (assoc a
                                (if (ts/many? db a-schema)
                                  (into (empty v) (map #(ref-wrapper conn %)) v)
                                  (ref-wrapper conn v))))))
             m
             m))

(defn comp-some [& fns]
  (some->> (keep identity fns) seq (apply comp)))

(defn compose-pull-xf [{:keys [default xform sort-by sort-by/dir skip limit]}]
  (when-let [fns (seq (cond-> ()
                              default (conj #(if (some? %) % default))
                              xform (conj xform)
                              sort-by (conj (case dir
                                              :desc #(clojure.core/sort-by sort-by (fn [a b] (compare b a)) %)
                                              #(clojure.core/sort-by sort-by %)))
                              skip (conj #(drop skip %))
                              limit (conj #(take limit %))))]
    (apply comp fns)))

(defn- -pull
  ([conn db ref-wrapper pullv e] (-pull conn db ref-wrapper pullv #{} e))
  ([conn db ref-wrapper pullv found e]
   (when-let [e (-resolve-e conn db e)]
     (reduce-kv
      (fn pull [m i pullexpr]
        (if (#{'* :*} pullexpr)
          (do
            (-depend-on-triple! conn db nil e nil nil)
            (if e
              (cond->> (dissoc (ts/eav db e) :db/id)
                       ref-wrapper
                       (-wrap-map-refs ref-wrapper conn db))
              m))
          (let [[a map-expr] (if (or (keyword? pullexpr) (list? pullexpr))
                               [pullexpr nil]
                               (first pullexpr))
                [a alias val-fn opts] (if (list? a)
                                        (let [{:as opts
                                               alias :as
                                               :or {alias (first a)}} (apply hash-map (rest a))
                                              a (first a)]
                                          (if (:db/id opts)
                                            [a :db/id #(vector a %) opts]
                                            [a alias (compose-pull-xf opts) opts]))
                                        [a a nil nil])
                is-reverse (u/reverse-attr? a)
                forward-a (cond-> a is-reverse u/forward-attr)
                a-schema (ts/get-schema db forward-a)
                is-ref (ts/ref? db a-schema)
                is-many (or (ts/many? db a-schema) is-reverse)
                val-fn (when-let [fns (seq (cond-> ()
                                                   val-fn (conj val-fn)
                                                   (and is-ref ref-wrapper) (conj (partial ref-wrapper conn))))]
                         (apply comp fns))
                v (when-some [v (cond-> (get* conn db a-schema nil e a)
                                        is-many not-empty)]
                    (cond (not is-ref) v
                          ;; ref without pull-expr
                          (nil? map-expr) v
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
                                                          (-pull conn db ref-wrapper pullv found %))]
                                           (if is-many
                                             (into [] (keep do-pull) v)
                                             (do-pull v)))
                                         v))]
                            refs  #_(cond-> refs (not is-many) first))

                          ;; cardinality/many
                          is-many (mapv #(-pull conn db ref-wrapper map-expr %) v)

                          ;; cardinality/one
                          :else (-pull conn db ref-wrapper map-expr v)))]
            (cond-> m
                    (some? v)
                    (assoc alias (wrap-v v is-many val-fn))))))
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
  ([conn pull-expr] (fn [e] (pull conn pull-expr e)))
  ([conn pull-expr e]
   (pull conn nil pull-expr e))
  ([conn {:keys [wrap-root wrap-ref]} pull-expr e]
   (let [e (cond-> e (instance? Entity e) :db/id)
         ref-wrapper (some-> wrap-ref make-ref-wrapper)
         root-wrapper (or wrap-root root-wrapper-default)
         db (ts/db conn)]
     (->> (-pull conn db ref-wrapper pull-expr e)
          (root-wrapper conn)))))

(defn partial-pull
  "Defines a 3-arity pull function with default options"
  [options]
  (fn pull-fn
    ([conn pull-expr]
     (fn [e] (pull-fn conn pull-expr e)))
    ([conn pull-expr e]
     (pull conn options pull-expr e))
    ([conn options-2 pull-expr e]
     (pull conn (merge options options-2) pull-expr e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Where
;;

(defn clause-filter [conn db clause]
  (if (or (fn? clause) (keyword? clause))
    (fn [entity] (clause entity))
    (let [[a v] clause]
      (cond
        ;; arbitrary function
        (fn? v) (fn [entity] (v (clojure.core/get entity a)))

        (or (keyword? v)
            (not (ts/ref? db (ts/get-schema db a)))) (fn [entity] (= v (clojure.core/get entity a)))

        :else
        ;; refs - resolve `v` lookup-refs & compare to :db/id of the thing
        (fn [entity] (= (resolve-v conn db (ts/get-schema db a) a v)
                        (:db/id (clojure.core/get entity a))))))))

(defn where
  [conn clauses]
  (let [db (ts/db conn)
        [clause & clauses] clauses
        _ (assert (not (fn? clause)) "where cannot begin with function (scans entire db)")
        entity-ids (if (keyword? clause)
                     (-ae conn db (ts/get-schema db clause) clause)
                     (let [[a v] clause
                           a-schema (ts/get-schema db a)]
                       (if (fn? v)
                         (filterv #(v (-eav conn db a-schema % a))
                                  (-ae conn db a-schema a))
                         (-ave conn db a-schema a v))))]
    (->> entity-ids
         (into #{}
               (comp (map (partial entity conn))
                     ;; additional clauses filter entities from step 1
                     (filter (if (seq clauses)
                               (apply every-pred (map #(clause-filter conn db %) clauses))
                               identity)))))))

(defn get
  "Read entity or attribute reactively"
  ([conn e]
   (-eav conn (ts/db conn) e))
  ([conn e a]
   (-eav conn (ts/db conn) e a))
  ([conn e a not-found]
   (u/some-or (get conn e a) not-found)))

(defn depend-on-triple! [conn e a v]
  (let [db (ts/db conn)]
    (-depend-on-triple! conn db (when a (ts/get-schema db a)) e a v)))

(defn transact!
  ([conn txs]
   (->> (ts/transact conn txs)
        (handle-report! conn)))
  ([conn txs options]
   (->> (ts/transact conn txs options)
        (handle-report! conn))))