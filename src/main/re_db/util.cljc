(ns re-db.util
  (:refer-clojure :exclude [random-uuid])
  (:require [clojure.set :as set])
  #?(:cljs (:require-macros re-db.util)))

(defn guard [x f] (when (f x) x))

(defn set-replace [s old new] (-> s (disj old) (conj new)))

(defn replace-toplevel [pmap forms]
  (map (fn [x]
         (if (list? x)
           (list* (pmap (first x) (first x)) (rest x))
           (pmap x x))) forms))

(defmacro support-clj-protocols
  "Given a cljs deftype/defrecord, replace cljs-specific protocols/methods with clj variants
   when expansion target is clj.

  NOTE: only works for the subset of protocols/methods listed below."
  [form]
  (if (:ns &env) ;; target is cljs
    form
    (replace-toplevel '{ILookup clojure.lang.ILookup
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

                        IWatchable clojure.lang.IRef
                        -add-watch addWatch
                        -remove-watch removeWatch} form)))

(defn random-uuid []
  #?(:cljs (cljs.core/random-uuid)
     :clj (java.util.UUID/randomUUID)))

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