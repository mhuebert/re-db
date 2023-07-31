(ns re-db.fast
  (:refer-clojure :exclude [get-in])
 #?(:cljs (:require-macros re-db.fast)))


;; `gets` impl from cljs.analyzer
;; https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/analyzer.cljc#L205

#?(:clj (def SENTINEL (Object.))
   :cljs (def SENTINEL (js-obj)))

(defn gets
  ([m k0 k1]
   (let [m (get m k0 SENTINEL)]
     (when-not (identical? m SENTINEL)
       (get m k1))))
  ([m k0 k1 k2]
   (let [m (get m k0 SENTINEL)]
     (when-not (identical? m SENTINEL)
       (let [m (get m k1 SENTINEL)]
         (when-not (identical? m SENTINEL)
           (get m k2))))))
  ([m k0 k1 k2 k3]
   (let [m (get m k0 SENTINEL)]
     (when-not (identical? m SENTINEL)
       (let [m (get m k1 SENTINEL)]
         (when-not (identical? m SENTINEL)
           (let [m (get m k2 SENTINEL)]
             (when-not (identical? m SENTINEL)
               (get m k3)))))))))

(defn gets-some
  ([m k0 k1]
   (when-some [m (m k0)]
     (m k1)))
  ([m k0 k1 k2]
   (when-some [m (m k0)]
     (when-some [m (m k1)]
       (m k2))))
  ([m k0 k1 k2 k3]
   (when-some [m (m k0)]
     (when-some [m (m k1)]
       (when-some [m (m k2)]
         (m k3))))))

(defmacro get-some-in
  "Get-in, stops at nil values"
  [m ks]
  (if (and (:ns &env)
           (vector? ks))
    `(some-> (get ~m ~(first ks))
             ~@(for [k (rest ks)]
                 (list 'clojure.core/get k)))
    `(clojure.core/get-in ~m ~ks)))


(defmacro get-in-objs
  "Lookups in javascript objects, keywords converted to strings"
  [m ks]
  (assert (vector? ks))
  `(j/get-in ~m ~(mapv (fn [k] (if (keyword? k)
                                 (subs (str k) 1)
                                 `(j/!get ~k ~'.-fqn))) ks)))



(defmacro defmemo-1 [name fsym]
  (if (:ns &env)
    `(let [cache# (volatile! {})]
       (defn ~(with-meta name {:tag 'function}) [x#]
         (if-some [res# (@cache# x#)]
           res#
           (let [res# (~fsym x#)]
             (vswap! cache# assoc x# res#)
             res#))))
    `(def ~name (memoize ~fsym))))

(defmacro if-found [[sym lookup-expr] then else]
  (let [sentinel `SENTINEL]
    `(let [~sym ~(concat lookup-expr (list sentinel))]
       (if (identical? ~sym ~sentinel)
         ~else
         ~then))))

(defmacro update! [m k f & args]
  `(let [m# ~m]
     (assoc! m# ~k (~f (m# ~k) ~@args))))

(defmacro update-seq! [m k f & args]
  `(let [m# ~m]
     (~'re-db.fast/assoc-seq! m# ~k (~f (m# ~k) ~@args))))

(defmacro update-some! [m k f & args]
  `(let [m# ~m]
     (~'re-db.fast/assoc-some! m# ~k (~f (m# ~k) ~@args))))

(defmacro update-index! [index [k1 k2] f & args]
  `(let [index# ~index
         index-a# (index# ~k1)
         index-v# (get index-a# ~k2)]
     (~'re-db.fast/assoc-seq! index# ~k1
      (~'re-db.fast/assoc-seq index-a# ~k2 (~f index-v# ~@args)))))

(defmacro assoc-index! [index [k1 k2] v]
  `(update! ~index ~k1 assoc ~k2 ~v))

(defmacro dissoc-index! [index [k1 k2]]
  `(update-seq! ~index ~k1 dissoc ~k2))

(defn assoc-seq! [m a v]
  (if (seq v)
    (assoc! m a v)
    (dissoc! m a)))

(defn assoc-seq [m a v]
  (if (seq v)
    (assoc m a v)
    (dissoc m a)))

(defn assoc-some! [m a v]
  (if (some? v)
    (assoc! m a v)
    (dissoc! m a)))

(defn assoc-some [m a v]
  (if (some? v)
    (assoc m a v)
    (dissoc m a)))

(defmacro update-db-index! [db [i x y] f & args]
  `(update! ~db ~i update-index! [~x ~y] ~f ~@args))

(defmacro assoc-db-index! [db [i x y] v]
  `(update! ~db ~i assoc-index! [~x ~y] ~v))

(defmacro dissoc-db-index! [db [i x y]]
  `(update! ~db ~i dissoc-index! [~x ~y]))

(defn comp6
  "Comps functions which receive acc as 1st value followed by 6 unchanged values"
  [fs]
  (let [[f1 f2 f3 f4 f5 f6 f7 f8] fs]
    (case (count fs)
      0 (fn [acc _ _ _ _ _ _] acc)
      1 f1
      2 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6)))
      3 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6)))
      4 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6)))
      5 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6)))
      6 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6)))
      7 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6) (f7 a1 a2 a3 a4 a5 a6)))
      8 (fn [acc a1 a2 a3 a4 a5 a6] (-> acc (f1 a1 a2 a3 a4 a5 a6) (f2 a1 a2 a3 a4 a5 a6) (f3 a1 a2 a3 a4 a5 a6) (f4 a1 a2 a3 a4 a5 a6) (f5 a1 a2 a3 a4 a5 a6) (f6 a1 a2 a3 a4 a5 a6) (f7 a1 a2 a3 a4 a5 a6) (f8 a1 a2 a3 a4 a5 a6)))
      (comp6 (cons (comp6 (take 8 fs))
                   (drop 8 fs))))))

(defn merge-maps [m1 m2]
  (if (some? m1)
    (merge m1 m2)
    m2)
  (merge m1 m2))

(defn mut-set! [o k v]
  #?(:cljs (doto o (unchecked-set (.-fqn ^Keyword k) v))
     :clj  (doto o (vswap! assoc k v))))
(defn mut-obj []
  #?(:cljs #js{}
     :clj  (volatile! {})))
(defn mut-get [o k]
  #?(:cljs (unchecked-get o (.-fqn ^Keyword k))
     :clj (get @o k)))
(defn mut-arr []
  #?(:cljs #js[]
     :clj (volatile! [])))
(defn mut-deref [x]
  #?(:cljs x :clj @x))
(defn mut-arr->vec [x]
  #?(:cljs (vec x)
     :clj @x))
(defn mut-push! [arr v]
  #?(:cljs (doto ^js arr (.push v))
     :clj  (doto arr (vswap! conj v))))