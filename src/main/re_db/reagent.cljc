(ns re-db.reagent
  (:require #?@(:cljs [[reagent.ratom :as ratom]])
            [applied-science.js-interop :as j]
            [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.reagent)))

;; TODO - figure out what this should look like in clj-land

(defmacro read-index! [conn index & args]
  (let [{e \e a \a v \v} (zipmap (name index) args)
        expr `(fast/gets @~conn ~index ~@args)]
    (if (:ns &env)
      `(~'re-db.reagent/cached-index-read ~conn ~e ~a ~v (fn [] ~expr))
      expr)))

#?(:clj (def ^:dynamic *ratom-context* nil))

(defn ratom-context [] #?(:cljs ratom/*ratom-context* :clj *ratom-context*))

(defn enter-cache! [ratom conn e a v]
  ;; add to cache
  (swap! conn assoc-in [:cached-readers e a v] ratom)
  ;; set up disposal
  #?(:cljs
     (some-> (ratom-context)
             (ratom/add-on-dispose!
              (fn []
                (js/setTimeout (fn []
                                 (when (empty? (.-watches ^clj ratom))
                                   (swap! conn update-in [:cached-readers e a] dissoc v))) 100)))))

  ratom)

(defn invalidate! [atom next-tx]
  (let [{:keys [f !tx]} (meta atom)]
    (when-not (== next-tx @!tx)
      (vreset! !tx next-tx)
      (reset! atom (f)))))

#?(:cljs
   ;; just for debugging
   (defn captured-patterns []
     (into #{}
           (keep (comp :pattern meta))
           (j/get (ratom-context) .-captured))))

(defn make-reader [conn e a v read-fn value]
  (let [meta {:f read-fn :!tx (volatile! 0) :pattern [e a v]}]
    (-> #?(:clj  (util/->MAtom value meta)
           :cljs (ratom/->RAtom value meta nil nil))
        (enter-cache! conn e a v))))

(defn cached-index-read [conn e a v read-fn]
  ;; evaluates & returns read-fn, caching the value,
  ;; recomputing when the given e-a-v pattern
  ;; is invalidated by a transaction on `conn`.

  ;; there can only be one reader per e-a-v pattern,
  ;; it is reused among consumers and cleaned up when
  ;; no longer observed.
  (if (ratom-context)
    @(or (fast/get-some-in @conn [:cached-readers e a v])
         (make-reader conn e a v read-fn (read-fn)))
    (read-fn)))

(defn keys-when [m vpred]
  (reduce-kv (fn [m a v]
               (cond-> m ^boolean (vpred v) (conj a)))
             #{}
             m))

(defn- many-a? [db-schema] (keys-when db-schema #(:many ^db/Schema %)))
(defn- ref-a? [db-schema] (keys-when db-schema #(:ref ^db/Schema %)))

(defn invalidate-readers!
  ;; given a re-db transaction, invalidates readers based on
  ;; patterns found in transacted datoms.
  [_conn {:as tx-report
          :keys [datoms]
          {:as db-after :keys [cached-readers schema]} :db-after}]
  (when cached-readers
    (let [tx (:tx db-after)
          many? (many-a? schema)
          ref? (ref-a? schema)
          found! (fn [^Reader reader]
                   (some-> reader (invalidate! tx)))
          trigger-patterns! (j/fn [^js [e a v pv :as datom]]

                              ;; triggers patterns for datom, with "early termination"
                              ;; for paths that don't lead to any invalidators.

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
      (doseq [datom datoms] (trigger-patterns! datom)))))