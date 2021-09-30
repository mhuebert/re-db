(ns re-db.fast
  (:refer-clojure :exclude [get-in]))

(defmacro get-in
  "Compiled version of get-in, faster than `get-in` function"
  [m ks]
  (if-not (vector? ks)
    `(clojure.core/get-in ~m ~ks)
    `(-> ~m
         ~@(for [k ks]
             (list 'clojure.core/get k)))))

(defmacro get-some-in
  "Get-in, stops at nil values"
  [m ks]
  (if-not (vector? ks)
    `(clojure.core/get-in ~m ~ks)
    `(some-> (get ~m ~(first ks))
             ~@(for [k (rest ks)]
                 (list 'clojure.core/get k)))))

(defmacro get-in-objs
  "Lookups in javascript objects, keywords converted to strings"
  [m ks]
  (assert (vector? ks))
  `(j/get-in ~m ~(mapv (fn [k] (if (keyword? k)
                                 (subs (str k) 1)
                                 `(j/!get ~k ~'.-fqn))) ks)))



(defmacro defmemo-1 [name fsym]
  `(let [cache# (volatile! {})]
     (defn ~name [x#]
       (if-some [res# (@cache# x#)]
         res#
         (let [res# (~fsym x#)]
           (vswap! cache# assoc x# res#)
           res#)))))

(defmacro if-found [[sym lookup-expr] then else]
  `(let [~sym ~(concat lookup-expr (list 're-db.fast/nf-sentinel))]
     (if (identical? ~sym ~'re-db.fast/nf-sentinel)
       ~else
       ~then)))

(defmacro invoke->
  "Like -> but calls each function using -invoke"
  {:added "1.0"}
  [x & forms]
  (loop [x x, forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (with-meta `(~'cljs.core/-invoke ~(first form) ~x ~@(next form)) (meta form))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(defmacro update! [m k f & args]
  `(let [m# ~m]
     (assoc! m# ~k (~f (m# ~k) ~@args))))

(def assoc-seq! 're-db.fast/assoc-seq!)
(def assoc-seq 're-db.fast/assoc-seq)
(def assoc-some 're-db.fast/assoc-some)
(def assoc-some! 're-db.fast/assoc-some!)

(defmacro update-seq! [m k f & args]
  `(let [m# ~m]
     (~assoc-seq! m# ~k (~f (m# ~k) ~@args))))

(defmacro update-some! [m k f & args]
  `(let [m# ~m]
     (~assoc-some! m# ~k (~f (m# ~k) ~@args))))

(defmacro update-index! [index [k1 k2] f & args]
  `(let [index# ~index
         index-a# (index# ~k1)
         index-v# (get index-a# ~k2)]
     (~assoc-seq! index# ~k1
      (~assoc-seq index-a# ~k2 (~f index-v# ~@args)))))

(defmacro assoc-index! [index [k1 k2] v]
  `(update! ~index ~k1 assoc ~k2 ~v))

(defmacro dissoc-index! [index [k1 k2]]
  `(update-seq! ~index ~k1 dissoc ~k2))

(defmacro update-db-index! [db [i x y] f & args]
  `(update! ~db ~i update-index! [~x ~y] ~f ~@args))

(defmacro assoc-db-index! [db [i x y] v]
  `(update! ~db ~i assoc-index! [~x ~y] ~v))

(defmacro dissoc-db-index! [db [i x y]]
  `(update! ~db ~i dissoc-index! [~x ~y]))