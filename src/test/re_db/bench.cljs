(ns re-db.bench
  (:require [applied-science.js-interop :as j]
            [re-db.core :as d]
            [re-db.read :as read]
            [datascript.core :as ds]
            [clojure.walk :as walk]
            [re-db.test-helpers :as th :refer [bench]]
            [re-db.fast :as fast]))

(comment
 ;; result: no difference between some? and undefined?
 (bench "undefined? vs some?"
        :undefined? #(let [o (if (rand-nth [true false])
                               (j/obj :x 1)
                               (j/obj))]
                       (undefined? (j/!get o :x)))
        :some? #(let [o (if (rand-nth [true false])
                          (j/obj :x 1)
                          (j/obj))]
                  (some? (j/!get o :x)))))

(defn ^string fqn [kw] (.-fqn ^clj kw))

(defn to-key [obj]
  (cond (keyword? obj) (fqn obj)
        (uuid? obj) (.-uuid ^clj obj)
        :else obj))

(defonce !canonicals #js{})
(defn canonical* [obj ^string k]
  (let [obj* (j/!get !canonicals k)]
    (if (undefined? obj*)
      (do (j/!set !canonicals k obj)
          obj)
      obj*)))
(defn canonical [obj]
  (cond (keyword? obj) (canonical* obj (fqn obj))
        (uuid? obj) (canonical* obj (.-uuid ^clj obj))
        true obj))

(comment
 (let [kws (mapv #(keyword (str "a/b" %)) (take 400 (range)))
       set-kws (take 200 kws)
       read-kws (take 200 (shuffle kws))
       write-kws (take 200 (shuffle kws))
       clj-set (set set-kws)
       clj-map (reduce #(assoc %1 %2 (rand-int 100)) {} set-kws)
       js-map (reduce #(do (.set %1 (canonical %2) (rand-int 100))
                           %1)
                      (js/Map.)
                      set-kws)
       js-set (let [s (js/Set.)]
                (doseq [k set-kws] (.add s (canonical k)))
                s)
       dup-set (fn [s] (let [s2 (js/Set.)] (doseq [v s] (.add s2 v)) s2))
       js-set-has (fn [s k] (.has s (canonical k)))
       js-set-add (fn [s obj] (doto s (.add (canonical obj))))
       js-obj (reduce #(j/!set %1 (to-key %2) (rand-int 100)) (j/obj) set-kws)
       js-obj-read #(j/!get %1 ^string (to-key %2))
       read-kws-str (mapv to-key read-kws)]
   (prn :=
        (= (mapv #(contains? clj-set %) read-kws)
           (mapv #(.has js-set (canonical %)) read-kws)))

   (let [rand-str #(str (rand-nth "abcdefghijklmnop")
                        (rand-nth "abcdefghijklmnop")
                        (rand-nth "abcdefghijklmnop"))
         ks (take 1000 (repeatedly #(case (rand-int 4)
                                      0 (rand-str)
                                      1 (keyword (rand-str) (rand-str))
                                      2 (random-uuid)
                                      3 (rand-int 9999999))))
         tk-cond (fn [obj]
                   (cond (keyword? obj) (fqn obj)
                         (uuid? obj) (.-uuid ^clj obj)
                         :else obj))
         tk-or (fn [obj]
                 (if (number? obj)
                   obj
                   (or (.-fqn ^clj obj)
                       (.-uuid ^clj obj)
                       obj)))]
     (bench "attr-key"
            :tk-or #(mapv tk-or ks)
            :tk-cond #(mapv tk-cond ks)
            :tk-known-kw #(mapv fqn ks)

            ))
   (bench "clj vs js set"
          :clj-set-read (fn [] (mapv #(contains? clj-set %) read-kws))
          :js-set-read (fn [] (mapv #(js-set-has js-set %) read-kws))
          :js-obj-contains (fn [] (mapv #(j/contains? js-obj %) read-kws))
          :clj-set-add (fn [] (reduce #(conj %1 %2) clj-set write-kws))
          :js-set-add (fn [] (let [#_#__js-set (dup-set js-set)] (doseq [k write-kws] (js-set-add js-set k)) js-set))
          :clj-map-read (fn [] (mapv #(get clj-map %) read-kws))
          :js-map-read (fn [] (mapv #(.get js-map (canonical %)) read-kws))
          :js-obj-read (fn [] (mapv #(js-obj-read js-obj %) read-kws))
          :js-fqn-read (fn [] (mapv #(j/!get js-obj (fqn %)) read-kws))
          :js-obj-read-2 (fn [] (mapv #(j/!get js-obj %) read-kws-str))
          )))
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
(def schema {:user/id {:db/unique :db.unique/identity}
             :user/pets {:db/valueType :db.type/ref
                         :db/cardinality :db.cardinality/many}
             :pet/owner {:db/valueType :db.type/ref}
             :user/name {:db/index true}})

(do



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

  (defn additional-tx [eav]
    (->> (seq eav)
         (shuffle)
         (take (Math/floor (/ (count eav) 5)))
         (mapv (fn [entity]
                 (merge {:db/id (:db/id entity)}
                        (if (:user/id entity)
                          (merge {:user/height (+ 80 (rand-int 100))
                                  :user/weight (+ 40 (rand-int 60))})
                          {:color (rand-nth (rand-nth ["black" "brown" "tan" "white" "spotted" "golden"]))})
                        (rand-map (rand-int 4)))))))

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
                     #_(d/transact! samples))
        get-eav #(-> (d/create-conn schema)
                     (doto (d/transact! %))
                     deref
                     :eav)]
    (let [samples (make-samples 100 5)]
      (js/performance.mark "db")
      (dotimes [_ 1000]
        (d/transact! (atom re-snap) samples))
      (js/performance.measure "db" "db"))


    (let [samples (make-samples 100 5)
          eav (get-eav samples)
          tx2 (additional-tx eav)
          tx3 (additional-tx eav)]
      (bench "transactions - 5 keys per map"
             "re-db     " #(doto (atom re-snap)
                             (d/transact! samples)
                             (d/transact! tx2)
                             (d/transact! tx3))
             "datascript" #(doto (atom ds-snap)
                             (ds/transact! samples)
                             (ds/transact! tx2)
                             (ds/transact! tx3))))

    (let [samples (make-samples 100 20)
          eav (get-eav samples)
          tx2 (additional-tx eav)
          tx3 (additional-tx eav)]
      (bench "transactions - 20 keys per map"
             "re-db     " #(doto (atom re-snap)
                             (d/transact! samples)
                             (d/transact! tx2)
                             (d/transact! tx3))
             "datascript" #(doto (atom ds-snap)
                             (ds/transact! samples)
                             (ds/transact! tx2)
                             (ds/transact! tx3))))

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

 (let [make-key #(keyword (str (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                               (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                               (rand-nth "abcdefghijklmnopqrs1234567890!$%&*"))
                          (str (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                               (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                               (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")))
       vs (take 100 (repeatedly make-key))
       m (apply hash-map vs)
       obj (clj->js (apply hash-map (map #(.-fqn ^clj %) vs)))

       ks (into (keys m) (take 100 (repeatedly make-key)))
       oks (map #(.-fqn ^clj %) ks)]
   (bench "lookups"
          :as-fn #(mapv (fn [k] (get m k)) ks)
          ;:get #(mapv (fn [k] (get m k)) ks)
          ;:-lookup #(mapv (fn [k] (-lookup m k)) ks)
          ;:as-fn/nf #(mapv (fn [k] (m k nil)) ks)
          ;:get/nf #(mapv (fn [k] (get m k nil)) ks)
          ;:-lookup/nf #(mapv (fn [k] (-lookup m k nil)) ks)
          :get-obj (fn [] (mapv (fn [^string k] (j/!get obj k)) oks))
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

 (defn ->obj-with-string-kws [m]
   ;; {:a/b 1} => {"a/b" 1}
   (walk/postwalk
    #(cond-> % (map? %) (->> (reduce-kv (fn [obj ^clj k v]
                                          (j/!set obj (.-fqn k) v)) #js{}))) m))

 (defn get-string-kw [obj ^clj kw] (j/get obj (.-fqn kw)))

 (let [m (reduce (fn [m k] (assoc m k (case (rand-int 2)
                                        0 {:db/valueType :db.type/ref}
                                        1 {:db/cardinality :db.cardinality/many}
                                        2 {}))) {} (take 1000 (repeatedly #(keyword (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")
                                                                                    (rand-nth "abcdefghijklmnopqrs1234567890!$%&*")))))
       ks (keys m)
       get-in #(get-in m [% :db/valueType])                 ;s (samples 1000)
       fast-get-in #(fast/get-in m [% :db/valueType])       ;s (samples 1000)
       string-kw-obj (->obj-with-string-kws m)
       get-string-kws (fn [^clj a]
                        (fast/get-in-objs string-kw-obj [a :db/valueType]))
       ks (shuffle (into []
                         (comp (take 1000) (mapcat identity))
                         (repeat ks)))]
   (prn :=? (= (mapv get-in ks)
               (mapv fast-get-in ks)
               (mapv get-string-kws ks)))
   (bench "get-in"
          :get-in #(mapv get-in ks)
          :fast-get-in #(mapv fast-get-in ks)
          :get-string-kws #(mapv get-string-kws ks))

   (bench "assoc"
          :clj #(reduce (fn [m k] (assoc m k (rand-int 10))) m ks)
          :clj-t #(persistent! (reduce (fn [m k] (assoc! m k (rand-int 10))) (transient m) ks))
          :js #(reduce (fn [obj ^clj k] (j/!set obj ^string (.-fqn k) (rand-int 10)))
                       (doto #js{} (js/Object.assign string-kw-obj))
                       ks)))

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
