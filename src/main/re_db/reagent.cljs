(ns re-db.reagent
  (:require [applied-science.js-interop :as j]
            [re-db.fast :as fast]
            [reagent.ratom :as ratom]))

;; modified from reagent.ratom/RAtom
(deftype Invalidator [^:mutable watches on-unwatched]
  IWatchable
  (-notify-watches [this old new] (-reset! this nil))
  (-add-watch [this key f]
    (set! watches (assoc watches key f)))
  (-remove-watch [this key]
    (set! watches (dissoc watches key))
    (when (empty? watches)
      (on-unwatched this))))

(defn depend-on! [^Invalidator this ^clj context]
  (j/!update context .-captured
             (fn [^array c]
               (if c
                 (j/push! c this)
                 (array this))))
  nil)


(j/defn trigger! [^Invalidator this]
  (reduce-kv (fn [_ k f] (f k this 0 1) nil) nil (.-watches this)))

(defn pattern-invalidators
  "Returns version-atoms associated with patterns.

  value-map is of form {<pattern-key> {<pattern> #{...set of values...}}}.
  pattern-map is of form {<pattern-key> #{...set of patterns...}}"
  [patterns listeners]
  (reduce-kv (fn [out pattern-k pattern-vs]
               (reduce (fn [out pattern-v]
                         (if-some [version (fast/get-in listeners [pattern-k pattern-v])]
                           (conj out version)
                           out)) out pattern-vs)) #{} patterns))

(defn invalidate! [conn patterns]
  (when-some [invalidators (:invalidators @conn)]
    (doseq [[pattern-k pattern-vs] patterns
            :let [invalidators (invalidators pattern-k)]
            :when (some? invalidators)]
      (doseq [pattern-v pattern-vs]
        (some-> (invalidators pattern-v) trigger!)))))

(defn make-invalidator [conn pattern-key pattern]
  (let [this (Invalidator. {} nil)]
    (set! (.-on-unwatched this)
          (fn []
            (js/setTimeout
             #(when (empty? (.-watches this))
                (swap! conn update-in [:invalidators pattern-key] dissoc pattern))
             100)))
    this))

(defn log-read! [conn pattern-key pattern]
  (when-some [context ratom/*ratom-context*]
    (if-let [inv (fast/get-in @conn [:invalidators pattern-key pattern])]
      (depend-on! inv context)
      (swap! conn assoc-in [:invalidators pattern-key pattern]
             (doto (make-invalidator conn pattern-key pattern)
               (depend-on! context)))))
  nil)

(defn guard [x f] (when (f x) x))

(defn- resolve-id
  "Copied from re-db.core."
  ([conn db-snap attr val]
   (log-read! conn :_av [attr val])
   (first (fast/get-in db-snap [:ave attr val]))))

(defn- lookup-ref
  "Returns lookup ref if one exists in id position"
  [kind pattern]
  (case kind
    :e__ (guard pattern vector?)
    :ea_ (guard (first pattern) vector?)
    nil))

(def ^:private empty-patterns
  "Map for building sets of patterns."
  {:e__ #{}                                                 ;; <entity id>
   :_a_ #{}                                                 ;; <attribute>
   :_av #{}                                                 ;; [<attribute>, <value>]
   :ea_ #{}})

(defn compr
  "Composes a collection of 2-arity reducing functions"
  [fs]
  (fn [x y]
    (reduce (fn [x f] (f x y)) x fs)))

(defn- datom-patterns
  "Returns a map of patterns matched by a list of datoms.
  Limits patterns to those listed in pattern-keys."
  ([datoms many?]
   (datom-patterns datoms many? [:e__ :ea_ :_av :_a_]))
  ([datoms many? pattern-keys]
   (let [f (compr (for [[k f] {:e__ #(update %1 :e__ conj (%2 0))
                               :ea_ #(update %1 :ea_ conj (subvec %2 0 2))
                               :_av #(if (many? (%2 1))
                                       (update %1 :_av
                                               (fn [_av]
                                                 (reduce
                                                  (fn [patterns v] (conj patterns [(%2 1) v]))
                                                  _av
                                                  (into (%2 2) (%2 3)))))
                                       (update %1 :_av conj [(subvec %2 1 3)
                                                             [(%2 1) (%2 3)]]))
                               :_a_ #(update %1 :_a_ conj (%2 1))}
                        :when (contains? pattern-keys k)]
                    f))]
     (reduce f empty-patterns datoms))))

(defn- many-attrs
  "Returns set of attribute keys with db.cardinality/schema"
  [schema]
  (reduce-kv (fn [s attr k-schema]
               (cond-> s
                       (j/get k-schema :many?) (conj attr))) #{} schema))

(defn filter-keys
  "Returns keys for which val passes valfn test"
  [m valfn]
  (->> m
       (reduce-kv (fn [ks k v] (cond-> ks (valfn v) (conj k))) #{})))

(defn invalidate-datoms! [conn {:keys [datoms]
                                {:keys [invalidators schema]} :db-after}]
  (invalidate!
   conn
   (datom-patterns datoms (many-attrs schema) (filter-keys invalidators seq))))

(comment
 (assert (= (datom-patterns [["e" "a" "v" "prev-v"]]
                            #{}
                            (keys empty-patterns))
            {:e__ #{"e"}
             :ea_ #{["e" "a"]}
             :_av #{["e" "v"] ["e" "prev-v"]}
             :_a_ #{"a"}})))