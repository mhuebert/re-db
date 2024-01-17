(ns re-db.read
  "Reactive API for re-db (with explicit connection argument)."
  (:refer-clojure :exclude [get])
  (:require [re-db.fast :as fast]
            [re-db.reactive :as r]
            [re-db.triplestore :as ts]
            [re-db.util :as u])
  #?(:cljs (:require-macros [re-db.read])))

;; TODO
;; - -depend-on-triple! - expect `e` to be resolved already

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Listeners
;;
;; A reactive signal per [e a v] pattern which invalidates listeners

(defonce !listeners (atom {}))

(defn- make-listener! [conn e a v]
  (let [listener (r/atom 0
                         :meta {:pattern       [e a v]      ;; for debugging
                                :dispose-delay 0}
                         :on-dispose (fn [_]
                                       (swap! !listeners u/dissoc-in [conn e a v])))]
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
      (let [result      (volatile! #{})
            collect!    #(some->> % (vswap! result conj))
            _           (ts/report-triples
                          (ts/db conn)
                          tx-report
                          (fn [e a v]
                            ;; triggers patterns for datom, with "early termination"
                            ;; for paths that don't lead to any invalidators.
                            (when-some [e (listeners e)]
                              (collect! (fast/gets-some e nil nil)) ;; e__
                              (collect! (fast/gets-some e a nil))) ;; ea_
                            (when-some [_ (listeners nil)]
                              (when-some [__ (_ nil)]
                                (collect! (__ v)))          ;; __v (used for refs)
                              (when-some [_a (_ a)]
                                (collect! (_a v))           ;; _av
                                (collect! (_a nil))))))     ;; _a_

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
    (let [a (when a (ts/datom-a db a))
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
       (or
         (first (ts/ave db a-schema a v))
         ;; only depend on triple if lookup ref is not found; these are IDs and we don't expect them to change.
         ;; TO TEST - edge cases?
         (-depend-on-triple! conn db a-schema nil a v)
         nil)))))

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
   (-depend-on-triple! conn db nil e nil nil)
   (ts/eav db e))
  ([conn db a-schema e a]
   (-depend-on-triple! conn db a-schema e a nil)
   (ts/eav db a-schema e a)))

(defn- -ave [conn db a-schema a v]
  (when-let [v (resolve-v conn db a-schema a v)]
    (-depend-on-triple! conn db a-schema nil a v)
    (ts/ave db a-schema a v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity API
;;

(defonce ^:dynamic *attribute-resolvers* {})

(declare entity)

(defn apply-ref-fn [f conn e]
  (if #?(:clj (keyword? e) :cljs false)
    e
    (f conn e)))

(defn some-val [is-many v]
  (if is-many
    (not (empty? v))
    (some? v)))

(defn peek* [conn db a-schema ref-fn e a]
  (when e
    (if (= :db/id a)
      e
      (if-let [resolver (*attribute-resolvers* a)]
        (resolver (entity conn e))
        (when-let [e (-resolve-e conn db e)]
          (let [is-reverse (u/reverse-attr? a)
                a          (cond-> a is-reverse u/forward-attr)
                is-ref     (ts/ref? db a-schema)
                v          (if is-reverse
                             (ts/ave db a-schema a e)
                             (ts/eav db a-schema e a))]     ;; [e a _]
            (if (and is-ref ref-fn)
              (let [is-many (ts/many? db a-schema)]
                (when (some-val is-many v)
                  (if (or is-reverse is-many)
                    (mapv (partial apply-ref-fn ref-fn conn) v)
                    (apply-ref-fn ref-fn conn v))))
              v)))))))

(defn get* [conn db a-schema ref-fn e a]
  (when e
    (if (= :db/id a)
      e
      (if-let [resolver (*attribute-resolvers* a)]
        (resolver (entity conn e))
        (let [is-reverse (u/reverse-attr? a)
              a          (cond-> a is-reverse u/forward-attr)
              is-ref     (ts/ref? db a-schema)
              v          (if is-reverse
                           (ts/ave db a-schema a e)
                           (ts/eav db a-schema e a))]
          (if is-reverse
            (-depend-on-triple! conn db a-schema nil a e)   ;; [_ a v]
            (-depend-on-triple! conn db a-schema e a nil))  ;; [e a _]
          (if (and is-ref ref-fn)
            (let [is-many (ts/many? db a-schema)]
              (when (some-val is-many v)
                (if (or is-reverse is-many)
                  (mapv (partial apply-ref-fn ref-fn conn) v)
                  (apply-ref-fn ref-fn conn v))))
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
    #?@(:cljs [IPrintWithWriter
               (-pr-writer [o writer opts] (-write writer (str "#re-db/entity[" e "]")))])
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
        (hash [conn e (ts/eav db e)])))

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
        (cond e-resolved? (get* conn db (ts/get-schema db a) entity e a)
              (= :db/id a) e)))
    (-lookup [o a nf]
      (case nf
        ::unwrapped
        (let [db (ts/db conn)]
          (re-db.read/-resolve-entity-e! conn db e e-resolved?)
          (cond e-resolved? (get* conn db (ts/get-schema db a) nil e a)
                (= :db/id a) e))
        (if-some [v (clojure.core/get o a)] v nf)))
    IDeref
    (-deref [this]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (when e-resolved?
          (-depend-on-triple! conn db nil e nil nil)
          (let [m (ts/eav db e)]
            (reduce-kv (fn [m a v]
                         (let [a-schema (ts/get-schema db a)]
                           (if (ts/ref? db a-schema)
                             (assoc m a (if (ts/many? db a-schema)
                                          (mapv #(entity conn %) v)
                                          (entity conn v)))
                             m)))
                       m
                       m)))))
    ISeqable
    (-seq [this] (seq @this))))

#?(:clj
   (defmethod print-method re_db.read.Entity
     [^re_db.read.Entity x w]
     (#'clojure.core/print-meta x w)
     (.write w "#")
     (.write w (.getName re_db.read.Entity))
     (#'clojure.core/print-map {:e (:db/id x)} #'clojure.core/pr-on w)))

(defn entity [conn e]
  (if (instance? Entity e)
    e
    (let [db (ts/db conn)
          e  (or (-resolve-e conn db e) e)]
      (->Entity conn e false nil))))

(defn touch [the-entity]
  (let [m    @the-entity
        conn (conn the-entity)
        db   (ts/db conn)]
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

(defn- -wrap-map-refs [ref-fn conn db m]
  (if ref-fn
    (reduce-kv (fn [m a v]
                 (let [a-schema (ts/get-schema db a)]
                   (cond-> m
                           (ts/ref? db a-schema)
                           (assoc a
                                  (if (ts/many? db a-schema)
                                    (into (empty v) (map (partial apply-ref-fn ref-fn conn)) v)
                                    (apply-ref-fn ref-fn conn v))))))
               m
               m)
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
  ([conn db lite? wrap-ref pullv e] (-pull conn db lite? wrap-ref pullv #{} e))
  ([conn db lite? ref-fn pullv found e]
   (when-let [e (-resolve-e conn db e)]
     (when lite? (-depend-on-triple! conn db nil e nil nil))
     (reduce-kv
       (fn pull [m i pullexpr]
         (if (#{'* :*} pullexpr)
           (do
             (when-not lite? (-depend-on-triple! conn db nil e nil nil))
             (->> (dissoc (ts/eav db e) :db/id)
                  (-wrap-map-refs ref-fn conn db)))
           (let [[a map-expr] (if (or (keyword? pullexpr) (seq? pullexpr))
                                [pullexpr nil]
                                (first pullexpr))
                 [a alias val-fn opts] (if (seq? a)
                                         (let [{:as   opts
                                                alias :as
                                                :or   {alias (first a)}} (apply hash-map (rest a))
                                               a (first a)]
                                           (if (:db/id opts)
                                             [a :db/id #(vector a %) opts]
                                             [a alias (compose-pull-xf opts) opts]))
                                         [a a nil nil])
                 is-reverse (u/reverse-attr? a)
                 forward-a  (cond-> a is-reverse u/forward-attr)
                 a-schema   (ts/get-schema db forward-a)
                 is-ref     (ts/ref? db a-schema)
                 is-many    (or (ts/many? db a-schema) is-reverse)
                 v          (let [v (if lite?
                                      (peek* conn db a-schema nil e a)
                                      (get* conn db a-schema nil e a))]
                              (cond (not is-ref) v
                                    (nil? map-expr) (wrap-v v is-many ref-fn) ;; ref without pull-expr
                                    ;; recurse
                                    (or (number? map-expr) (#{'... :...} map-expr))
                                    (let [recursions (if (= 0 map-expr) false map-expr)]
                                      (when v
                                        (if recursions
                                          (let [found   (conj found e)
                                                pullv   (if (number? recursions)
                                                          ;; decrement recurse parameter
                                                          (update-in pullv [i forward-a] dec)
                                                          pullv)
                                                do-pull #(if (and (= :... recursions) (found %))
                                                           %
                                                           (-pull conn db lite? ref-fn pullv found %))]
                                            (if is-many
                                              (into [] (keep do-pull) v)
                                              (do-pull v)))
                                          v)))

                                    ;; cardinality/many
                                    is-many (mapv #(-pull conn db lite? ref-fn map-expr %) v)

                                    ;; cardinality/one
                                    :else (-pull conn db lite? ref-fn map-expr v)))]
             (cond-> m
                     (some-val is-many v)
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
  ([conn {:keys [wrap-root wrap-ref lite?]
          :or   {lite? false}} pull-expr e]
   (let [e  (:db/id e e) #_(cond-> e (instance? Entity e) :db/id)
         db (ts/db conn)]
     (cond->> (-pull conn db lite? wrap-ref pull-expr e)
              wrap-root
              (wrap-root conn)))))

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
  (let [db         (ts/db conn)
        [clause & clauses] clauses
        _          (assert (not (fn? clause)) "where cannot begin with function (scans entire db)")
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
   (let [db (ts/db conn)]
     (when-let [e (-resolve-e conn db e)]
       (-eav conn db e))))
  ([conn e a]
   (let [db (ts/db conn)]
     (when-let [e (-resolve-e conn db e)]
       (-eav conn db (ts/get-schema db a) e a))))
  ([conn e a not-found]
   (u/some-or (get conn e a) not-found)))

(defn depend-on-triple! [conn e a v]
  (let [db         (ts/db conn)
        e-resolved (-resolve-e conn db e)]
    (when (or (nil? e) e-resolved)
      (-depend-on-triple! conn db
                          (when a (ts/get-schema db a))
                          e-resolved
                          a
                          v))))

(defn transact!
  ([conn txs]
   (->> (ts/transact conn txs)
        (handle-report! conn)))
  ([conn txs options]
   (->> (ts/transact conn txs options)
        (handle-report! conn))))
