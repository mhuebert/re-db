(ns re-db.patterns
  (:refer-clojure :exclude [clone])
  (:require [re-db.reactive :as r]
            [re-db.fast :as fast]
            [re-db.util :as util]
            [re-db.protocols :as rp]
            [re-db.in-memory :as rm])
  #?(:cljs (:require-macros re-db.patterns)))

(defonce !listeners (atom {}))
(def !ratoms !listeners)

(defn invalidate-report-datoms!
  ;; given a re-db transaction, invalidates readers based on
  ;; patterns found in transacted datoms.
  [conn report doto-triples]
  (when-let [listeners (@!listeners conn)]
    (let [result (volatile! #{})
          collect! #(some->> % (vswap! result conj ))
          _ (doto-triples (fn [e a v]
                            ;; triggers patterns for datom, with "early termination"
                            ;; for paths that don't lead to any invalidators.
                            (when-some [e (listeners e)]
                              (collect! (fast/gets-some e nil nil)) ;; e__
                              (collect! (fast/gets-some e a nil))) ;; ea_
                            (when-some [_ (listeners nil)]
                              (when-some [__ (_ nil)]
                                (collect! (__ v))) ;; __v (used for refs)
                              (when-some [_a (_ a)]
                                (collect! (_a v)) ;; _av
                                (collect! (_a nil))))) ;; _a_
                          report)
          listeners @result]
      #_(prn :invalidating (mapv (comp :pattern meta) listeners))
      (doseq [listener listeners] (r/invalidate! listener)))))

(defn listen-patterns [conn]
  (doto conn
    (rm/listen! ::patterns (fn [conn report]
                             (invalidate-report-datoms! conn report rm/doto-triples)))))

(def reactive-conn (comp listen-patterns rm/create-conn))

(defonce ^:dynamic *conn* (reactive-conn)) ;; if present, reads can subscribe to changes
(defonce ^:dynamic *db* nil) ;; point-in-time db value

(defn current-conn [] *conn*)
(defn current-db
  "*db* pins down the value of db, for as-of queries. If not present, we deref the current connection."
  [conn]
  (rp/get-db conn *db*))

(defn clone
  "Creates a copy of conn (without existing listeners)"
  [conn]
  (listen-patterns (atom @conn)))

(defn make-listener [conn e a v]
  (r/->RAtom nil
             {}
             [(fn [_] (swap! !ratoms util/dissoc-in [conn e a v]))]
             {:pattern [e a v]}))

(declare resolve-pattern)

(defn depend-on-triple!
  ([e a v] (depend-on-triple! *conn* e a v))
  ([conn e a v]
   (when (and conn (r/owner))
     (let [[e a v :as triple] (resolve-pattern conn (rp/db conn) e a v)]
       @(or (fast/gets-some @!ratoms conn e a v)
            (doto (make-listener conn e a v)
              (->> (swap! !ratoms assoc-in [conn e a v]))))
       triple))))

(defn ae
  ([a] (ae *conn* (current-db *conn*) a))
  ([conn db a]
   (assert db)
   (some-> conn (depend-on-triple! nil a nil))
   (rp/ae db a)))

(defn resolve-lookup-ref
  ([e] (resolve-lookup-ref *conn* (current-db *conn*) e))
  ([conn db [a v :as e]]
   (assert db)
   (assert (rp/unique? conn a) "Lookup ref attribute must be unique")
   (if (vector? v) ;; nested lookup ref
     (resolve-lookup-ref conn db [a (resolve-lookup-ref conn db v)])
     (when v
       (depend-on-triple! conn nil a v)
       (first (rp/ave db a v))))))

(defn resolve-e
  ([e] (resolve-e *conn* (current-db *conn*) e))
  ([conn db e]
   (assert db)
   (if (vector? e)
     (resolve-lookup-ref conn db e)
     (:db/id e e))))

(defn resolve-v
  ([a v] (resolve-v *conn* (current-db *conn*) a v))
  ([conn db a v]
   (assert db)
   (cond->> v
            (and v (rp/ref? db a))
            (resolve-e conn db))))

(defn eav
  ([e] (eav *conn* (current-db *conn*) e))
  ([e a] (eav *conn* (current-db *conn*) e a))
  ([conn db e]
   (assert db)
   (when-let [id (resolve-e conn db e)]
     (depend-on-triple! conn id nil nil)
     (rp/eav db id)))
  ([conn db e a]
   (assert db)
   (some-> conn (depend-on-triple! e a nil))
   (rp/eav db e a)))

(defn ave
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  ([a v] (ave *conn* (current-db *conn*) a v))
  ([conn db a v]
   (assert db)
   (when-let [v (resolve-v conn db a v)]
     (some-> conn (depend-on-triple! nil a v))
     (rp/ave db a v))))

(defn resolve-pattern
  ([e a v] (resolve-pattern *conn* (current-db *conn*) e a v))
  ([conn db e a v]
   (assert db)
   [(when e (resolve-e conn db e))
    (when a (rp/internal-e db a))
    (when v (resolve-v conn db a v))]))

(defmacro once
  "Evaluates body with `db` bound, but without tracking dependencies"
  [db & body]
  `(binding [*db* ~db] ~@body))

(defn ->conn
  "Accepts a conn or a schema, returns conn"
  [conn-or-schema]
  (if (map? conn-or-schema)
    (reactive-conn conn-or-schema)
    conn-or-schema))

(defmacro with-conn
  [conn & body]
  `(binding [*conn* (->conn ~conn)] ~@body))