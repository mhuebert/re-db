(ns re-db.reagent
  (:require #?@(:cljs [[reagent.ratom :as ratom]])
            [applied-science.js-interop :as j]
            [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.util :as util]
            [re-db.history :as history]
            [clojure.string :as str])
  #?(:cljs (:require-macros re-db.reagent)))

(defmacro read-index! [conn index & args]
  (let [{e \e a \a v \v} (zipmap (name index) args)
        expr `(fast/gets @~conn ~index ~@args)]
    (if (:ns &env)
      `(~'re-db.reagent/cached-index-read ~conn ~e ~a ~v (fn [] ~expr))
      expr)))

#?(:clj (def ^:dynamic *ratom-context* nil))

(defn ratom-context [] #?(:cljs ratom/*ratom-context* :clj *ratom-context*))

(defprotocol IReaction
  (recompute! [reader])
  (invalidate! [reader tx]))

;; modified from reagent.ratom/RAtom
(defprotocol ICaptureWatchers
  (capture! [context watcher]))

(util/support-clj-protocols
  (deftype Reader [!watchers
                   !on-unwatched
                   e a v
                   f
                   !value
                   !tx]
    IReaction
    (recompute! [this]
      (vreset! !value (f)))
    (invalidate! [this next-tx]
     ;; only trigger! once per transaction
      (when-not (== next-tx @!tx)
        (vreset! !tx next-tx)
        (let [newval (recompute! this)]
          (reduce-kv (fn [_ k f] (f k this #?(:cljs js/undefined :clj nil) newval) nil) nil @!watchers))))
    IWatchable
    #?(:cljs
       (-notify-watches [this old new] (throw (js/Error. "-notify-watches Unsupported"))))
    (-add-watch [this key f]
      (vswap! !watchers assoc key f))
    (-remove-watch [this key]
      (vswap! !watchers dissoc key)
      (when (empty? @!watchers)
        (@!on-unwatched this)))
    IReset
    (-reset! [this newval] (vreset! !value newval))
    IDeref
    (-deref [this] @!value)
    ICaptureWatchers
    (capture! [context watcher]
      (prn ::reader-does-not-capture-readers))))

#?(:cljs
   (extend-protocol ICaptureWatchers
     ratom/Reaction
     (capture! [^ratom/Reaction this watchable]
       (j/!update this .-captured
                  (fn [^array c]
                    (if c
                      (j/push! c watchable)
                      (array watchable)))))))

#?(:cljs
   ;; just for debugging
   (defn captured-patterns []

     ;; reagent internals
     (->> (j/get (ratom-context) .-captured)
          (reduce (fn [patterns ^Reader i]
                    (cond-> patterns
                            (instance? Reader i)
                            (conj [(.-e i) (.-a i) (.-v i)])))
                  #{}))))

(defn make-reader [conn e a v read-fn value]
  (->Reader (volatile! {})
            (volatile!
             (fn [^Reader reader]
               #?(:cljs
                  (js/setTimeout
                   #(when (empty? @(.-!watchers reader))
                      (swap! conn update-in [:cached-readers e a] dissoc v))
                   100))))
            e
            a
            v
            read-fn
            (volatile! value)
            (volatile! nil)))

(defn cached-index-read [conn e a v read-fn]
  ;; evaluates & returns read-fn, caching the value,
  ;; recomputing when the given e-a-v pattern
  ;; is invalidated by a transaction on `conn`.

  ;; there can only be one reader per e-a-v pattern,
  ;; it is reused among consumers and cleaned up when
  ;; no longer observed.
  (if-some [context (ratom-context)]
    (let [reader (or (fast/get-some-in @conn [:cached-readers e a v])
                     (let [reader (make-reader conn e a v read-fn (read-fn))]
                       (swap! conn assoc-in [:cached-readers e a v] reader)
                       reader))]
      (do (capture! context reader)
          @reader))
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
    (let [tx (history/current-tx db-after)
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