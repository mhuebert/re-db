(ns re-db.reagent
  (:require [applied-science.js-interop :as j]
            [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reactive :as r])
  #?(:cljs (:require-macros re-db.reagent)))

(defmacro read-index! [conn index & args]
  (let [{e \e a \a v \v} (zipmap (name index) args)
        expr `(fast/gets @~conn ~index ~@args)]
    (if (:ns &env)
      `(~'re-db.reagent/cached-index-read ~conn ~e ~a ~v (fn [] ~expr))
      expr)))

(defn captured-patterns []
  (->> @r/*captured-derefs*
       (into #{} (map (comp :pattern meta)))))

(defn make-reader [conn e a v read-fn]
  (r/make-reaction read-fn
                   :on-dispose #?(:cljs (swap! conn update-in [:cached-readers e a] dissoc v))

                   ;; only in dev?
                   :meta {:pattern [e a v]}))

(defn cached-index-read [conn e a v read-fn]
  ;; evaluates & returns read-fn, caching the value,
  ;; recomputing when the given e-a-v pattern
  ;; is invalidated by a transaction on `conn`.

  ;; there can only be one reader per e-a-v pattern,
  ;; it is reused among consumers and cleaned up when
  ;; no longer observed.
  (if (r/owner)
    @(or (fast/get-some-in @conn [:cached-readers e a v])
         (doto (make-reader conn e a v read-fn)
           (->> (swap! conn assoc-in [:cached-readers e a v]))))
    (read-fn)))

(defn keys-when [m vpred]
  (reduce-kv (fn [m a v]
               (cond-> m (vpred v) (conj a)))
             #{}
             m))

(defn- many-a? [db-schema] (keys-when db-schema db/many?))
(defn- ref-a? [db-schema] (keys-when db-schema db/ref?))

(defn invalidate-readers!
  ;; given a re-db transaction, invalidates readers based on
  ;; patterns found in transacted datoms.
  [_conn {:as tx-report
          :keys [datoms]
          {:as db-after :keys [cached-readers schema]} :db-after}]
  (when cached-readers
    (let [many? (many-a? schema)
          ref? (ref-a? schema)
          !found (volatile! #{})
          found! #(some->> % (vswap! !found conj))
          trigger-patterns! (j/fn [^js [e a v pv :as datom]]

                              ;; triggers patterns for datom, with "early termination"
                              ;; for paths that don't lead to any invalidators.

                              ;; TODO - dissoc-in found-readers reduce repetitive lookups

                              (when-let [e (cached-readers e)]
                                ;; e__
                                (found! (fast/gets-some e nil nil))
                                ;; ea_
                                (found! (fast/gets-some e a nil)))
                              (when-let [_ (cached-readers nil)]
                                (let [is-many (many? a)
                                      is-ref (ref? a)]
                                  (when-let [__ (_ nil)]
                                    (when is-ref
                                      (if is-many
                                        (do (doseq [v v] (found! (__ v)))
                                            (doseq [pv pv] (found! (__ pv))))
                                        (do (found! (__ v))
                                            (found! (__ pv))))))
                                  (when-some [_a (_ a)]
                                    (if is-many
                                      ;; _av
                                      (do
                                        (doseq [v v] (found! (_a v)))
                                        (doseq [pv pv] (found! (_a pv))))
                                      (do
                                        (found! (_a v))
                                        (found! (_a pv))))
                                    ;; _a_
                                    (found! (_a nil))))))]
      (doseq [datom datoms] (trigger-patterns! datom))
      (doseq [reader @!found] (r/invalidate! reader)))))