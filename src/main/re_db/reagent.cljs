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

(defn depend-on! [^Reader reader ^clj context]
  (capture-watchable! context reader)
  @reader)

;; just for debugging
(defn captured-patterns []
  ;; reagent internals
  (->> (j/get ratom/*ratom-context* .-captured)
       (reduce (fn [patterns ^Reader i]
                 (cond-> patterns
                         (instance? Reader i)
                         (conj [(.-e i) (.-a i) (.-v i)])))
               #{})))

(defn recompute! [^Reader reader]
  (let [newval (j/call reader .-f)]
    (reset! reader newval)
    newval))

(j/defn invalidate! [^Reader reader tx]
  (when
   ;; only trigger! once per transaction
   (not (== tx (j/!get reader .-tx)))
    (j/!set reader .-tx tx)
    (let [newval (recompute! reader)]
      (reduce-kv (fn [_ k f] (f k reader js/undefined newval) nil) nil (.-watches reader)))))

(defn make-reader [conn e a v read-fn value]
  (let [reader (Reader. {} nil e a v read-fn value)]
    (set! (.-on-unwatched reader)
          (fn []
            (js/setTimeout
             #(when (empty? (.-watches reader))
                (swap! conn update-in [:cached-readers e a] dissoc v))
             100)))
    reader))

(defn logged-read* [conn e a v read-fn]
  ;; evaluates & returns read-fn, caching the value,
  ;; recomputing when the given e-a-v pattern
  ;; is invalidated by a transaction on `conn`.

  ;; there can only be one reader per e-a-v pattern,
  ;; it is reused among consumers and cleaned up when
  ;; no longer observed.
  (if-some [context ratom/*ratom-context*]
    (let [reader (or (fast/get-some-in @conn [:cached-readers e a v])
                     (let [reader (make-reader conn e a v read-fn (read-fn))]
                       (swap! conn assoc-in [:cached-readers e a v] reader)
                       reader))]
      (depend-on! reader context))
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