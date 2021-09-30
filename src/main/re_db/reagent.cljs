(ns re-db.reagent
  (:require [applied-science.js-interop :as j]
            [re-db.fast :as fast]
            [reagent.ratom :as ratom]
            [re-db.core :as db]))

;; modified from reagent.ratom/RAtom
(deftype Invalidator [^:mutable watches on-unwatched e a v]
  IWatchable
  (-notify-watches [this old new] (-reset! this nil))
  (-add-watch [this key f]
    (set! watches (assoc watches key f)))
  (-remove-watch [this key]
    (set! watches (dissoc watches key))
    (when (empty? watches)
      (on-unwatched this))))

(defn depend-on! [^Invalidator this ^clj context]
  ;; Reagent internals
  (j/!update context .-captured
             (fn [^array c]
               (if c
                 (j/push! c this)
                 (array this))))
  nil)

;; just for debugging
(defn current-patterns []
  ;; reagent internals
  (->> (j/get ratom/*ratom-context* .-captured)
       (reduce (fn [patterns ^Invalidator i]
                 (cond-> patterns
                         (instance? Invalidator i)
                         (conj [(.-e i) (.-a i) (.-v i)])))
               #{})))

(j/defn trigger! [tx ^Invalidator this]
  (when
   ;; only trigger! once per transaction
   (not (== tx (j/!get this :tx)))
    (j/!set this :tx tx)
    (reduce-kv (fn [_ k f] (f k this 0 1) nil) nil (.-watches this))))

(defn make-invalidator [conn e a v]
  (let [this (Invalidator. {} nil e a v)]
    (set! (.-on-unwatched this)
          (fn []
            (js/setTimeout
             #(when (empty? (.-watches this))
                (swap! conn update-in [:invalidators e a] dissoc v))
             100)))
    this))

(defn log-read! [conn e a v]
  (when-some [context ratom/*ratom-context*]
    (if-let [inv (fast/get-in @conn [:invalidators e a v])]
      (depend-on! inv context)
      (swap! conn assoc-in [:invalidators e a v]
             (doto (make-invalidator conn e a v)
               (depend-on! context)))))
  nil)

(defn- many-attrs [db-schema]
  (->> db-schema
       (reduce-kv (fn [attrs a a-schema]
                    (cond-> attrs
                            (db/many? a-schema)
                            (conj a)))
                  #{})))

(defn invalidate-datoms! [_conn {:keys [datoms tx]
                                 {:keys [invalidators schema]} :db-after}]
  (when invalidators
    (let [many? (many-attrs schema)
          found! (fn [invalidator]
                   (when (some? invalidator)
                     (trigger! tx invalidator)))
          trigger-patterns! (j/fn [^js [e a v pv :as datom]]
                              ;; triggers patterns for datom, with "early termination"
                              ;; for paths that don't lead to any invalidators.
                              (when-some [e (invalidators e)]
                                ;; e__
                                (found! (fast/get-in e [nil nil]))
                                ;; ea_
                                (found! (fast/get-in e [a nil])))
                              (when-some [_a (fast/get-in invalidators [nil a])]
                                (if (many? a)
                                  ;; _av
                                  (do
                                    (doseq [v v] (found! (_a v)))
                                    (doseq [pv pv] (found! (_a pv))))
                                  (do
                                    (found! (_a v))
                                    (found! (_a pv))))
                                ;; _a_
                                (found! (_a nil))))]
      (doseq [datom datoms] (trigger-patterns! datom)))))