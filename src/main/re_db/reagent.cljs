(ns re-db.reagent
  (:require [applied-science.js-interop :as j]
            [re-db.fast :as fast]
            [reagent.ratom :as ratom]))

;; modified from reagent.ratom/RAtom
(deftype Invalidator [^:mutable watches on-unwatched pattern-key pattern]
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

(defn current-patterns []
  (some->> (j/get ratom/*ratom-context* .-captured)
           (reduce (fn [m ^Invalidator i]
                     (cond-> m
                             (instance? Invalidator i)
                             (update (.-pattern-key i)
                                     (fnil conj #{})
                                     (.-pattern i)))) {})))

(j/defn trigger! [^Invalidator this]
  (reduce-kv (fn [_ k f] (f k this 0 1) nil) nil (.-watches this)))

(defn invalidate! [conn patterns]
  (when-some [invalidators (:invalidators @conn)]
    (doseq [[pattern-k pattern-vs] patterns
            :let [invalidators (invalidators pattern-k)]
            :when (some? invalidators)]
      (doseq [pattern-v pattern-vs]
        (some-> (invalidators pattern-v) trigger!)))))

(defn make-invalidator [conn pattern-key pattern]
  (let [this (Invalidator. {} nil pattern-key pattern)]
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
   :ae #{}                                                 ;; <attribute>
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
   (datom-patterns datoms many? [:e__ :ea_ :_av :ae]))
  ([datoms many? pattern-keys]
   (let [f (compr (for [[k f] {:e__ (j/fn [patterns ^js [e]] (update patterns :e__ conj e))
                               :ea_ (j/fn [patterns ^js [e a]] (update patterns :ea_ conj [e a]))
                               :_av (j/fn [patterns ^js [_e a v pv]]
                                      (if (many? a)
                                        (update patterns :_av
                                                (fn [_av]
                                                  (reduce
                                                   (fn [patterns v]
                                                     (conj patterns [a v]))
                                                   _av
                                                   (into v pv))))
                                        (update patterns :_av conj [a v] [a pv])))
                               :ae (fn [patterns [_ a]] (update patterns :ae conj a))}
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
             :ae #{"a"}})))