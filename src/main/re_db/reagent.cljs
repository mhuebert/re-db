(ns re-db.reagent
  (:require [applied-science.js-interop :as j]
            [re-db.fast :as fast]
            [reagent.ratom :as ratom]
            [re-db.core :as db])
  (:require-macros re-db.reagent))

;; modified from reagent.ratom/RAtom
(deftype Reader [^:mutable watches on-unwatched e a v f ^:mutable value]
  IWatchable
  (-notify-watches [this old new] (-reset! this nil))
  (-add-watch [this key f]
    (set! watches (assoc watches key f)))
  (-remove-watch [this key]
    (set! watches (dissoc watches key))
    (when (empty? watches)
      (on-unwatched this)))
  IReset
  (-reset! [this newval] (set! value newval))
  IDeref
  (-deref [this] value))

;; Reagent internals
(defn capture-watchable! [context watchable]
  (j/!update ^clj context .-captured
             (fn [^array c]
               (if c
                 (j/push! c watchable)
                 (array watchable)))))

(defn depend-on! [^Reader this ^clj context]
  (capture-watchable! context this)
  @this)

;; just for debugging
(defn captured-patterns []
  ;; reagent internals
  (->> (j/get ratom/*ratom-context* .-captured)
       (reduce (fn [patterns ^Reader i]
                 (cond-> patterns
                         (instance? Reader i)
                         (conj [(.-e i) (.-a i) (.-v i)])))
               #{})))

(defn recompute! [^Reader this]
  (let [newval (j/call this .-f)]
    (reset! this newval)
    newval))

(j/defn invalidate! [^Reader this tx]
  (when
   ;; only trigger! once per transaction
   (not (== tx (j/!get this .-tx)))
    (j/!set this .-tx tx)
    (let [newval (recompute! this)]
      (reduce-kv (fn [_ k f] (f k this js/undefined newval) nil) nil (.-watches this)))))

(defn make-reader [conn e a v read-fn value]
  (let [this (Reader. {} nil e a v read-fn value)]
    (set! (.-on-unwatched this)
          (fn []
            (js/setTimeout
             #(when (empty? (.-watches this))
                (swap! conn update-in [:cached-readers e a] dissoc v))
             100)))
    this))

(defn logged-read* [conn e a v read-fn]
  ;; evaluates & returns read-fn, caching the value,
  ;; recomputing when the given e-a-v pattern
  ;; is invalidated by a transaction on `conn`.

  ;; there can only be one reader per e-a-v pattern,
  ;; it is reused among consumers and cleaned up when
  ;; no longer observed.
  (if-some [context ratom/*ratom-context*]
    (if-let [inv (fast/get-some-in @conn [:cached-readers e a v])]
      (depend-on! inv context)
      (let [inv (make-reader conn e a v read-fn (read-fn))]
        (swap! conn assoc-in [:cached-readers e a v] inv)
        (depend-on! inv context)))
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
  [_conn {:keys [datoms tx]
          {:keys [cached-readers schema]} :db-after}]
  (when cached-readers
    (let [many? (keys-when schema db/many?)
          ref? (keys-when schema db/ref?)
          found! (fn [^Reader reader]
                   (some-> reader (invalidate! tx)))
          _ (cached-readers nil)
          __ (when _ (_ nil))
          trigger-patterns! (j/fn [^js [e a v pv :as datom]]

                              ;; triggers patterns for datom, with "early termination"
                              ;; for paths that don't lead to any invalidators.

                              (when-some [e (cached-readers e)]
                                ;; e__
                                (found! (fast/get-some-in e [nil nil]))
                                ;; ea_
                                (found! (fast/get-some-in e [a nil])))
                              (when _
                                (let [is-many (many? a)
                                      is-ref (ref? a)]
                                  (when __
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