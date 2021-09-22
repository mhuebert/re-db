(ns re-db.bench
  (:require [applied-science.js-interop :as j]
            [re-db.core :as d]
            [re-db.read :as read]
            [datascript.core :as ds]
            [clojure.walk :as walk]
            [re-db.test-helpers :as th :refer [bench]]))

(comment
  (let [datoms (mapv vec (partition 4 (take 1000 (repeatedly #(rand-int 999999)))))
        #_#_comp4'd (d/comp4
                 [(fn [m e a v pv]
                    (assoc-in m [e a] v))
                  (fn [m e a v pv]
                    (assoc-in m [a v] e))
                  (fn [m e a v pv]
                    (assoc-in m [v a] e))])
        comp1'd (d/comp1
                 [(fn [m [e a v pv]]
                    (assoc-in m [e a] v))
                  (fn [m [e a v pv]]
                    (assoc-in m [a v] e))
                  (fn [m [e a v pv]]
                    (assoc-in m [v a] e))])
        fs [(fn [m [e a v pv]]
              (assoc-in m [e a] v))
            (fn [m [e a v pv]]
              (assoc-in m [a v] e))
            (fn [m [e a v pv]]
              (assoc-in m [v a] e))]
        run1 (fn [fs init vs]
               (reduce (fn [acc v] (reduce #(%2 %1 v) acc fs)) init vs))
        ]

    (bench "feeding datom through comp'd fns vs as vector"
           #_#_:comp4 #(reduce (fn [m [e a v pv]] (comp4'd m e a v pv)) {} datoms)
           :comp1 #(reduce (fn [m datom] (comp1'd m datom)) {} datoms)
           :run1 #(run1 fs {} datoms))

    ))
(do

 (def schema {:user/id {:db/unique :db.unique/identity}
              :user/pets {:db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/many}
              :pet/owner {:db/valueType :db.type/ref}
              :user/name {:db/index true}})

 (defn rand-map [map-size]
   (merge (zipmap (take (Math/ceil (/ map-size 2))
                        (repeatedly (comp keyword str random-uuid)))
                  (range))
          (zipmap (take (Math/ceil (/ map-size 2))
                        (repeatedly #(keyword (rand-nth "abcdefghijklmnop"))))
                  (range))))

 (defn make-samples [n map-size]
   (into []
         (mapcat
          (fn [i]
            (let [pet-ids (set (for [j (range 1 (rand-int 5))]
                                 (+ i (/ j 10))))]
              (conj
               (for [id pet-ids]
                 (merge {:db/id id
                         :pet/id id
                         :pet/name (str "pet.name." i)
                         :color (rand-nth ["black" "brown" "tan" "white" "spotted" "golden"])
                         :pet/owner i}
                        (rand-map (- map-size 5))))
               (merge {:db/id i
                       :user/id (str "user.id." i)
                       :user/name (str "user.name." i)
                       :user/pets pet-ids
                       :user/height (+ 80 (rand-int 100))}
                      (rand-map (- map-size 5)))))
            ))
         (range 1 (inc n))))


 ;;;;;;;;;;;;;;;;;;;;;;;;;;
 ;; TRANSACT

 ;; with small entities (5 attrs)
 ;; - re-db is ~5x faster
 ;; with large entities (20 attrs)
 ;; - re-db is 10x faster

 ;; Overall Chrome is ~4x faster than Safari

 (let [ds-snap @(-> (ds/create-conn schema)
                    #_(doto (ds/transact! samples)))
       re-snap @(-> (d/create-conn schema)
                    #_(d/transact! samples))]
   (let [samples (make-samples 100 5)]
     (bench "transactions - 5 keys per map"
            :datascript/transact!
            #(ds/transact! (atom ds-snap) samples)
            :re-db/transact!
            #(d/transact! (atom re-snap) samples)))
   (let [samples (make-samples 100 20)]
     (bench "transactions - 20 keys per map"
            :datascript/transact!
            #(ds/transact! (atom ds-snap) samples)
            :re-db/transact!
            #(d/transact! (atom re-snap) samples)))

   (comment
    (let [ids (map :db/id (take 10 (shuffle samples)))
          re-conn (-> (atom re-snap) (d/transact! samples))
          ds-conn (doto (atom ds-snap) (ds/transact! samples))]
      (bench "lookups"
             "datascript entity lookup"
             #(mapv (fn [id] (:user/id (ds/entity @ds-conn id))) ids)
             "re-db tracked entity lookup"
             #(mapv (fn [id] (:user/id (read/entity re-conn id))) ids)
             "re-db tracked get lookup"
             #(mapv (fn [id] (read/get re-conn id :user/id)) ids)
             "re-db peek"
             #(mapv (fn [id] (read/peek re-conn id :user/id)) ids))))))

(comment
 ;; `some` vs truthiness
 ;;
 ;; `some` (in `when-some` or `some?`) checks for nil, whereas truthiness also checks for false.
 ;; it is only slightly faster (~10%).
 (let [m (zipmap "abcdefghijklmnopqrstuvwxyz" (repeatedly #(rand-nth [nil false 1 2 3 \a \b \c \d])))]
   (bench "lookups, some vs implicit"
          :when-let
          #(when-let [x (get m (rand-nth "abcdefghijklmnopqrstuvwxyz"))] true)
          :when-some
          #(when-some [x (get m (rand-nth "abcdefghijklmnopqrstuvwxyz"))] true)
          ;; added some copies here to observe how noisy measurements are
          :when-let-2
          #(when-let [x (get m (rand-nth "abcdefghijklmnopqrstuvwxyz"))] true)
          :when-some-2
          #(when-some [x (get m (rand-nth "abcdefghijklmnopqrstuvwxyz"))] true))))

(comment

 ;;;;;;;;;;;;;;;;;;;;;;;;;;
 ;; lookups
 ;;
 ;; not much difference between any of these lookups

 (let [m (apply hash-map (take 1000 (repeatedly #(keyword (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                                                          (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")))))
       ks (keys m)]
   (bench "lookups"
          :as-fn #(mapv (fn [k] (m k)) ks)
          :get #(mapv (fn [k] (get m k)) ks)
          :-lookup #(mapv (fn [k] (-lookup m k)) ks)
          :as-fn/nf #(mapv (fn [k] (m k nil)) ks)
          :get/nf #(mapv (fn [k] (get m k nil)) ks)
          :-lookup/nf #(mapv (fn [k] (-lookup m k nil)) ks)
          )))

(comment

 ;;;;;;;;;;;;;;;;;;;;;;;;;;
 ;; memoization
 ;;
 ;; fast/memo-1 is ~2x faster then core/memoize. core/memoize is slower than plain read/reverse-attr*.

 (let [kws (take 1000 (repeatedly #(keyword (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                                            (rand-nth "abcdefghijklmnopqrs1234567890!$%&*"))))
       simple-memo (memoize read/reverse-attr*)]
   (bench "memo"
          :no-memo #(mapv read/reverse-attr* kws)
          :memoize #(mapv simple-memo kws)
          ;; volatile instead of atom, hardcoded arity
          :fast-memo-1 #(mapv read/reverse-attr kws))))

(comment
 ;;;;;;;;;;;;;;;;;;;;;;;;;;
 ;; Nested lookups
 ;;
 ;; comparing consecutive get-in vs fast/get-in, ie. consecutive get
 ;;   get-in uses reduce to loop through keys,
 ;;   vs (-> x (get y) (get z))
 ;; also looks at javascript object lookups,
 ;; - with conversion of keywords to strings (:a/b => "a/b")
 ;; - and nested objects (:a/b => {"a" {"b" ..}}.
 ;;
 ;; RESULT
 ;; fast-get-in and nested object lookups are ~equivalent in speed. no need for js objects.
 ;; regular get-in and conversion of keywords to strings are significantly slower.

 (defn ->obj-with-nested-kws [m]
   ;; {:a/b 1} => {"a" {"b": 1}}
   (walk/postwalk
    #(cond-> % (map? %) (->> (reduce-kv (fn [obj k v]
                                          (if-some [ns (namespace k)]
                                            (j/assoc-in! obj [ns (name k)] v)
                                            (j/assoc! obj (name k) v))) #js{}))) m))

 (defn get-nested-kw [obj kw]
   (if-some [ns (namespace kw)]
     (j/get-in obj [ns (name kw)])
     (j/get obj (name kw))))

 (defn ->obj-with-string-kws [m]
   ;; {:a/b 1} => {"a/b" 1}
   (walk/postwalk
    #(cond-> % (map? %) (->> (reduce-kv (fn [obj k v]
                                          (if-some [ns (namespace k)]
                                            (j/assoc! obj (str ns "/" (name k)) v)
                                            (j/assoc! obj (name k) v))) #js{}))) m))

 (defn get-string-kw [obj kw]
   (if-some [ns (namespace kw)]
     (j/get obj (str ns "/" (name kw)))
     (j/get obj (name kw))))

 (let [get-in #(get-in schema [% :db/valueType])            ;s (samples 1000)
       fast-get-in #(d/fast-get-in schema [% :db/valueType]) ;s (samples 1000)
       nested-kws (->obj-with-nested-kws schema)
       get-nested-kws #(-> nested-kws (get-nested-kw %) (j/get-in ["db" "valueType"]))
       string-kws (->obj-with-string-kws schema)
       get-string-kws #(-> string-kws (get-string-kw %) (j/get-in ["db" "valueType"]))
       ks (shuffle (into []
                         (comp (take 1000) (mapcat identity))
                         (repeat [:user/id :user/name :user/pets :pet/id :pet/name :color :pet/owner :user/address :pet/food-preference])))]
   (prn :=? (= (mapv (comp identity get-in) ks)
               (mapv (comp identity fast-get-in) ks)
               (mapv (comp identity get-nested-kws) ks)
               (mapv (comp identity get-string-kws) ks)))
   (simple-benchmark [f get-in] (mapv f ks) 100)
   (simple-benchmark [f fast-get-in] (mapv f ks) 100)
   (simple-benchmark [f get-nested-kws] (mapv f ks) 100)
   (simple-benchmark [f get-string-kws] (mapv f ks) 100))

 (comment

  ;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; reduce-kv vs iterating over an array
  ;;
  ;; investigating a performance optimization found in reagent RAtom
  ;;
  ;; RESULT
  ;; in Chrome there is very little difference between reduce-kv vs array iter.
  ;; in Safari, array iter is 3x faster.

  (let [watches {:a (fn [x] x)
                 :b (fn [x] x)
                 :c (fn [x] x)
                 :d (fn [x] x)}
        watches-array (let [a #js[]]
                        ;; Copy watches to array for speed
                        (reduce-kv #(doto %1 (.push %2) (.push %3)) a watches)
                        a)]
    (bench "reduce-kv vs array"
           " :reduce-kv"
           #(reduce-kv (fn [_ k f] (f k) nil) nil watches)
           " :array"
           #(let [len (alength watches-array)]
              (loop [i 0]
                (when (< i len)
                  (let [k (aget watches-array i)
                        f (aget watches-array (inc i))]
                    (f k))
                  (recur (+ 2 i)))))
           "with new array"
           (fn []
             (let [watches-array (let [a #js[]]
                                   ;; Copy watches to array for speed
                                   (reduce-kv #(doto %1 (.push %2) (.push %3)) a watches)
                                   a)
                   len (alength watches-array)]
               (loop [i 0]
                 (when (< i len)
                   (let [k (aget watches-array i)
                         f (aget watches-array (inc i))]
                     (f k))
                   (recur (+ 2 i)))))))))

 (comment

  ;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; clj maps - vector keys vs nested lookups
  ;;
  ;; is it faster to look up v in {:a {:b v}} or {[:a :b] v}?
  ;;
  ;; RESULT
  ;; ~equivalent
  ;;
  ;; Chrome is 7x faster than Safari

  (let [paths (->> (take 10000 (repeat #(cond->> (rand-int 9999999)
                                                 (even? (rand-int 1))
                                                 (keyword (rand-nth "abcdefghijklmnopqrstuvwxyz")))))
                   (partition 5)
                   (mapv vec))
        ks (mapv (comp vec seq) (shuffle paths))
        flat-m (reduce #(assoc %1 %2 %2) {} paths)
        nested-m (reduce #(assoc-in %1 %2 %2) {} paths)]
    (bench "flat vs nested map lookups"
           :flat
           (fn [] (mapv flat-m ks))
           :nested
           (fn [] (mapv #(get-in nested-m %) ks))))))
