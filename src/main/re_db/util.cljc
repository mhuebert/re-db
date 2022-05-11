(ns re-db.util
  (:refer-clojure :exclude [random-uuid boolean])
  (:require [clojure.set :as set]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-db.fast :as fast])
  #?(:cljs (:require-macros [re-db.util :as u])))

#?(:cljs
   (do
     ;; Reagent compatibility
     (goog-define reagent-compat true)
     (def !reagent-notify-deref (delay (u/js-resolve-sym reagent.ratom/notify-deref-watcher!)))
     (defn reagent-notify-deref-watcher! [derefed]
       (when reagent-compat
         (when-some [f @!reagent-notify-deref] (f derefed))))))

(defn guard [x f] (when (f x) x))

(defn set-replace [s old new] (-> s (disj old) (conj new)))

(defn replace-toplevel [pmap forms]
  (map (fn [x]
         (if (list? x)
           (list* (pmap (first x) (first x)) (rest x))
           (pmap x x))) forms))

(def prot-substitutions
  '{ILookup clojure.lang.ILookup
    -lookup valAt

    ISeqable clojure.lang.Seqable
    -seq seq

    IDeref clojure.lang.IDeref
    -deref deref

    IReset clojure.lang.IAtom
    -reset! reset

    IMeta clojure.lang.IMeta
    -meta meta

    IWithMeta clojure.lang.IObj
    -with-meta withMeta

    IFn clojure.lang.IFn
    -invoke invoke

    IHash clojure.lang.IHashEq
    -hash hasheq

    IEquiv Object
    -equiv equals

    ISwap clojure.lang.IAtom
    -swap! swap

    IWatchable clojure.lang.IRef
    -add-watch addWatch
    -remove-watch removeWatch})


(defn replace-protocols [pmap form]
  (let [preamble (into [] (take-while (complement (every-pred symbol? #(str/starts-with? (name %) "I")))) form)
        body (drop (count preamble) form)]
    (concat preamble
            (->> body
                 (partition-by symbol?)
                 (partition 2)
                 (reduce (fn [m [[k] vs]] (update m (pmap k k) (fnil into []) vs)) {})
                 (mapcat (fn [[k fns]] (into [k]
                                             (map (fn [[n & args]] (list* (pmap n n) args)))
                                             fns)))))))

(comment
 (replace-protocols prot-substitutions
                    '(deftype x []
                       ISwap
                       (-swap! [x])
                       (-swap! [x y])
                       IReset
                       (-reset! [x])
                       (-reset! [x y])
                       IOther
                       (other [x]))))

(defmacro support-clj-protocols
  "Given a cljs deftype/defrecord, replace cljs-specific protocols/methods with clj variants
   when expansion target is clj.

  NOTE: only works for the subset of protocols/methods listed below."
  [form]
  (if (:ns &env) ;; target is cljs
    form
    (replace-protocols prot-substitutions form)))

(defn random-uuid []
  #?(:cljs (cljs.core/random-uuid)
     :clj  (java.util.UUID/randomUUID)))

(defmacro swap-> [ref & args]
  `(let [ref# ~ref]
     (swap! ref# (fn [val#] (-> val# ~@args)))))

(defmacro swap->> [ref & args]
  `(let [ref# ~ref]
     (swap! ref# (fn [val#] (->> val# ~@args)))))

(defn set-diff [s1 s2]
  (cond (identical? s1 s2) nil
        (nil? s1) [s2 nil]
        (nil? s2) [nil s1]
        :else (let [added (guard (set/difference s2 s1) seq)
                    removed (guard (set/difference s1 s2) seq)]
                (when (or added removed)
                  [added removed]))))

(defmacro js-resolve-sym [sym]
  `(j/get-in js/window ~(let [parts (conj (vec (str/split (str (namespace sym)) #"\.")) (name sym))]
                          (mapv munge parts))))

(defn reverse-attr? [a]
  (= \_ (.charAt (name a) 0)))

(defn reverse-attr* [attr]
  (keyword (namespace attr) (str "_" (name attr))))

(fast/defmemo-1 reverse-attr reverse-attr*)

(defn forward-attr* [attr]
  (keyword (namespace attr) (subs (name attr) 1)))

(fast/defmemo-1 forward-attr forward-attr*)

(defmacro defprotocol-once
  "a defprotocol that never redefines the protocol"
  [name & args]
  `(defonce ~(symbol (str "_" name))
     (defprotocol ~name ~@args)))

(defmacro some-or [& forms]
  (loop [forms (reverse forms)
         out nil]
    (if (empty? forms)
      out
      (recur (rest forms)
             `(if-some [v# ~(first forms)]
                v#
                ~out)))))

;; copied from https://github.com/clojure/core.incubator/blob/4f31a7e176fcf4cc2be65589be113fc082243f5b/src/main/clojure/clojure/core/incubator.clj#L63
(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; modified from https://github.com/clojure/core.incubator/blob/4f31a7e176fcf4cc2be65589be113fc082243f5b/src/main/clojure/clojure/core/incubator.clj#L63
(defn disj-in
  "Removes value from set at path. Any empty sets or maps that result
  will not be present in the new structure."
  [m [k & ks :as path] v]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (disj-in nextmap ks v)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (let [new-set (disj (get m k) v)]
      (if (seq new-set)
        (assoc m k new-set)
        (dissoc m k)))))

(defmacro bool [form] (vary-meta form assoc :tag 'bool))