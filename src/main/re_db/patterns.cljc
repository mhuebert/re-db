(ns re-db.patterns
  (:refer-clojure :exclude [clone])
  (:require [re-db.reactive :as r]
            [re-db.fast :as fast]
            [re-db.util :as util]
            [re-db.protocols :as rp]
            [re-db.in-memory :as mem])
  #?(:cljs (:require-macros re-db.patterns)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Listeners
;;
;; A reactive signal per [e a v] pattern which invalidates listeners

(defonce !listeners (atom {}))

(defn clear-listeners! [] (swap! !listeners empty))

(defn make-listener [conn e a v]
  (r/->RAtom nil
             {}
             [(fn [_] (swap! !listeners util/dissoc-in [conn e a v]))]
             {:pattern [e a v]}))


(defn handle-report!
  "Invalidate readers for a tx-report from conn based on datoms transacted"
  [conn tx-report]
  (when tx-report
    (if-let [listeners (@!listeners conn)]
      (let [result (volatile! #{})
            collect! #(some->> % (vswap! result conj))
            _ (rp/doto-report-triples
               (rp/db conn)
               (fn [e a v]
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
               tx-report)
            invalidated @result]
        #_(prn :invalidating (mapv (comp :pattern meta) invalidated))
        (doseq [listener invalidated] (r/invalidate! listener))
        (assoc tx-report ::handled (count invalidated)))
      tx-report)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic variables for the current database connection & value

;; reads are tracked per connection (a reference type, the value changes with each transaction)
(defonce ^:dynamic *conn* (mem/create-conn))

;; the point-in-time value of the conn (db) can optionally be overridden
(defonce ^:dynamic *db* nil)

(defn current-conn [] *conn*)

(defn current-db
  ([] (current-db *conn* *db*))
  ([conn] (current-db conn *db*))
  ([conn db] (or db (rp/db conn))))

(defn clone
  "Creates a copy of conn (without existing listeners)"
  [conn]
  (atom @conn))

(declare resolve-pattern)

(defn depend-on-triple!
  ([e a v] (depend-on-triple! *conn* e a v))
  ([conn e a v]
   (when (and conn (r/owner))
     (let [[e a v :as triple] (resolve-pattern conn (rp/db conn) e a v)]
       @(or (fast/gets-some @!listeners conn e a v)
            (doto (make-listener conn e a v)
              (->> (swap! !listeners assoc-in [conn e a v]))))
       triple))))

(defn ae
  ([a] (ae *conn* (current-db *conn*) a))
  ([conn db a]
   (some-> conn (depend-on-triple! nil a nil))
   (rp/ae db a)))

(defn resolve-lookup-ref
  ([e] (resolve-lookup-ref *conn* (current-db *conn*) e))
  ([conn db [a v :as e]]
   (assert (rp/unique? db a) "Lookup ref attribute must be unique")
   (if (vector? v) ;; nested lookup ref
     (resolve-lookup-ref conn db [a (resolve-lookup-ref conn db v)])
     (when v
       (depend-on-triple! conn nil a v)
       (first (rp/ave db a v))))))

(defn resolve-e
  ([e] (resolve-e *conn* (current-db *conn*) e))
  ([conn db e]
   (if (vector? e)
     (resolve-lookup-ref conn db e)
     (:db/id e e))))

(defn resolve-v
  ([a v] (resolve-v *conn* (current-db *conn*) a v))
  ([conn db a v]
   (cond->> v
            (and v (rp/ref? db a))
            (resolve-e conn db))))

(defn eav
  ([e] (eav *conn* (current-db *conn*) e))
  ([e a] (eav *conn* (current-db *conn*) e a))
  ([conn db e]
   (when-let [id (resolve-e conn db e)]
     (depend-on-triple! conn id nil nil)
     (rp/eav db id)))
  ([conn db e a]
   (some-> conn (depend-on-triple! e a nil))
   (rp/eav db e a)))

(defn ave
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  ([a v] (ave *conn* (current-db *conn*) a v))
  ([conn db a v]
   (when-let [v (resolve-v conn db a v)]
     (some-> conn (depend-on-triple! nil a v))
     (rp/ave db a v))))

(defn resolve-pattern
  ([e a v] (resolve-pattern *conn* (current-db *conn*) e a v))
  ([conn db e a v]
   [(some->> e (resolve-e conn db))
    (some->> a (rp/datom-a db))
    (some->> v (resolve-v conn db a))]))

(defmacro once
  "Evaluates body with `db` bound, but without tracking dependencies"
  [db & body]
  `(binding [*db* ~db] ~@body))

(defn ->conn
  "Accepts a conn or a schema, returns conn"
  [conn-or-schema]
  (if (map? conn-or-schema)
    (mem/create-conn conn-or-schema)
    conn-or-schema))

(defmacro with-conn
  [conn & body]
  `(binding [*conn* (->conn ~conn)] (assert *conn*) ~@body))