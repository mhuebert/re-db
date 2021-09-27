(ns re-db.bench
  (:refer-clojure :exclude [cat])
  (:require [applied-science.js-interop :as j]
            [re-db.core :as rd]
            [re-db.read :as read]
            [datascript.core :as d]
            [taoensso.encore :as enc]
            [meander.epsilon :as m]
            [ribelo.doxa :as dx]
            [clojure.walk :as walk]
            [re-db.test-helpers :as th :refer [bench]]
            [re-db.fast :as fast]))

(def schema {:user/pets {:db/valueType :db.type/ref
                         :db/cardinality :db.cardinality/many}
             :pet/owner {:db/valueType :db.type/ref}
             :user/name {:db/index true}
             :db/ref-id rd/indexed
             :friend {:db/valueType   :db.type/ref
                      #_#_:db/cardinality :db.cardinality/many}
             :alias   {:db/cardinality :db.cardinality/many}

             ;:name rd/indexed
             ;:age rd/indexed-ae
             ;:last-name rd/indexed-ae
             ;:sex rd/indexed
             })

;; obj vs Map
(comment
 (let [all-ks (mapv (partial str "some-key-") (take 500 (range)))
       write-ks (vec (take 200 (shuffle all-ks)))
       read-ks (vec (take 200 (shuffle all-ks)))
       write-m! (fn [m ks]
                  (doseq [k ks]
                    (.set m k 10))
                  m)
       write-obj! (fn [obj ks]
                    (doseq [k ks]
                      (unchecked-set obj k 10))
                    obj)]
   (let [o (write-obj! #js{} write-ks)
         m (write-m! (js/Map.) write-ks)]
     (bench "reading"
            :es6-Map #(let [res #js[]] (doseq [k read-ks] (.push res (.get m k))))
            :js-obj #(let [res #js[]] (doseq [k read-ks] (.push res (unchecked-get o k))))))

   (bench "js map vs obj, writing"
          :es6-Map #(let [m (js/Map.)]
                      (doseq [k all-ks] (.set m k 10))
                      m)
          :js-obj #(let [o #js{}]
                     (doseq [k all-ks] (unchecked-set o k 10))
                     o))))

;; undefined vs some?
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

;; canonical
(do
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
          true obj)))

;; objs Maps maps
(comment
 (let [keyword-keys (mapv #(keyword (str "a/b" %)) (take 400 (range)))
       set-kws (take 200 keyword-keys)
       keys-to-read (take 200 (shuffle keyword-keys))
       write-kws (take 200 (shuffle keyword-keys))
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
       read-kws-str (mapv to-key keys-to-read)]
   (prn :=
        (= (mapv #(contains? clj-set %) keys-to-read)
           (mapv #(.has js-set (canonical %)) keys-to-read)))

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
          :clj-set-read (fn [] (mapv #(contains? clj-set %) keys-to-read))
          :js-set-read (fn [] (mapv #(js-set-has js-set %) keys-to-read))
          :js-obj-contains (fn [] (mapv #(j/contains? js-obj %) keys-to-read))
          :clj-set-add (fn [] (reduce #(conj %1 %2) clj-set write-kws))
          :js-set-add (fn [] (let [#_#__js-set (dup-set js-set)] (doseq [k write-kws] (js-set-add js-set k)) js-set))
          :clj-map-read (fn [] (mapv #(get clj-map %) keys-to-read))
          :js-map-read (fn [] (mapv #(.get js-map (canonical %)) keys-to-read))
          :js-obj-read (fn [] (mapv #(js-obj-read js-obj %) keys-to-read))
          :js-fqn-read (fn [] (mapv #(j/!get js-obj (fqn %)) keys-to-read))
          :js-obj-read-2 (fn [] (mapv #(j/!get js-obj %) read-kws-str))
          )))

;; transact into dbs
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

  (let [ds-snap @(-> (d/create-conn schema)
                     #_(doto (d/transact! samples)))
        re-snap @(-> (rd/create-conn schema)
                     #_(rd/transact! samples))
        get-eav #(-> (rd/create-conn schema)
                     (doto (rd/transact! %))
                     deref
                     :eav)]
    (let [samples (make-samples 100 5)]
      (js/performance.mark "db")
      (dotimes [_ 1000]
        (rd/transact! (atom re-snap) samples))
      (js/performance.measure "db" "db"))

    (let [samples (make-samples 200 10)
          re-snap (deref (doto (atom re-snap)
                           (rd/transact! samples)))
          ds-snap (deref (doto (atom ds-snap)
                           (d/transact! samples)))
          id (-> (get-eav samples) ffirst)
          tx [[:db/add id :person/name "herbert"]
              [:db/add id :pet/name "priscilla"]]]
      (bench "small transactions"
             "re-db     " #(-> (atom re-snap)
                               (rd/transact! tx))
             "datascript" #(-> (atom ds-snap)
                               (d/transact! tx))))

    (let [samples (make-samples 100 5)
          eav (get-eav samples)
          tx2 (additional-tx eav)
          tx3 (additional-tx eav)]
      (bench "transactions - 5 keys per map"
             "re-db     " #(doto (atom re-snap)
                             (rd/transact! samples)
                             (rd/transact! tx2)
                             (rd/transact! tx3))
             "datascript" #(doto (atom ds-snap)
                             (d/transact! samples)
                             (d/transact! tx2)
                             (d/transact! tx3))))

    (let [samples (make-samples 100 20)
          eav (get-eav samples)
          tx2 (additional-tx eav)
          tx3 (additional-tx eav)]
      (bench "transactions - 20 keys per map"
             "re-db     " #(doto (atom re-snap)
                             (rd/transact! samples)
                             (rd/transact! tx2)
                             (rd/transact! tx3))
             "datascript" #(doto (atom ds-snap)
                             (d/transact! samples)
                             (d/transact! tx2)
                             (d/transact! tx3))))



    (comment
     (let [ids (map :db/id (take 10 (shuffle samples)))
           re-conn (-> (atom re-snap) (rd/transact! samples))
           ds-conn (doto (atom ds-snap) (d/transact! samples))]
       (bench "lookups"
              "datascript entity lookup"
              #(mapv (fn [id] (:user/id (d/entity @ds-conn id))) ids)
              "re-db tracked entity lookup"
              #(mapv (fn [id] (:user/id (read/entity re-conn id))) ids)
              "re-db tracked get lookup"
              #(mapv (fn [id] (read/get re-conn id :user/id)) ids)
              "re-db peek"
              #(mapv (fn [id] (read/peek re-conn id :user/id)) ids))))))

;; some vs truthy
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

;; cljs map lookups
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

;; memoize
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

;; nested lookups
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

;;; doxa bench


(do
  (let [next-eid (volatile! 0)]

    (defn random-man []
      {:db/id (vswap! next-eid inc)
       :name (rand-nth ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"])
       :last-name (rand-nth ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"])
       :alias (vec
               (repeatedly (rand-int 10) #(rand-nth ["A. C. Q. W." "A. J. Finn" "A.A. Fair" "Aapeli" "Aaron Wolfe" "Abigail Van Buren" "Jeanne Phillips" "Abram Tertz" "Abu Nuwas" "Acton Bell" "Adunis"])))
       :age (rand-int 100)
       :salary (rand-int 100000)
       :friend (rand-int 20000)})

    (defn random-fruit []
      {:fruit/id (vswap! next-eid inc)
       :name (rand-nth ["Avocado" "Grape" "Plum" "Apple" "Orange"])
       :price (rand-int 100)})

    (defn random-vegetable []
      {:vegetable/id (vswap! next-eid inc)
       :name (rand-nth ["Onion" "Cabbage" "Pea" "Tomatto" "Lettuce"])
       :price (rand-int 100)})

    (defn random-car []
      {:car/id (vswap! next-eid inc)
       :name (rand-nth ["Audi" "Mercedes" "BMW" "Ford" "Honda" "Toyota"])
       :price (rand-int 100)})

    (defn random-animal []
      {:animal/id (vswap! next-eid inc)
       :name (rand-nth ["Otter" "Dog" "Panda" "Lynx" "Cat" "Lion"])
       :price (rand-int 100)})

    (defn random-cat []
      {:cat/id (vswap! next-eid inc)
       :name (rand-nth ["Traditional Persian" "Ocicat" "Munchkin cat" "Persian cat" "Burmese cat"])
       :price (rand-int 100)})

    (defn random-dog []
      {:dog/id (vswap! next-eid inc)
       :name (rand-nth ["Croatian Shepherd" "Deutch Langhaar" "Miniature Pincher" "Italian Sighthound" "Jack Russell Terrier"])
       :price (rand-int 100)})

    (defn random-country []
      {:country/id (vswap! next-eid inc)
       :name (rand-nth ["Seychelles" "Greenland" "Iceland" "Bahrain" "Bhutan"])
       :price (rand-int 100)})

    (defn random-language []
      {:language/id (vswap! next-eid inc)
       :name (rand-nth ["Malagasy" "Kashmiri" "Amharic" "Inuktitut" "Esperanto"])
       :price (rand-int 100)})

    (defn random-marijuana-strain []
      {:marijuana/id (vswap! next-eid inc)
       :name (rand-nth ["Lemonder" "Black-Mamba" "Blueberry-Space-Cake" "Strawberry-Amnesia"])
       :price (rand-int 100)})

    (defn random-planet []
      {:planet/id (vswap! next-eid inc)
       :name (rand-nth ["Pluto" "Saturn" "Venus" "Mars" "Jupyter"])
       :price (rand-int 100)}))

  (defn make-n [f n] (->> (repeatedly f)
                          (take n)
                          shuffle))

  (def data100k (into []
                      (mapcat #(make-n % 10000))
                      [random-fruit
                      random-vegetable
                      random-car
                      random-animal
                      random-cat
                      random-dog
                      random-country
                      random-language
                      random-marijuana-strain
                      random-planet]))

  (def people50k (make-n random-man 50000))

  (defn add-ids [data] (mapv
                        (fn [m]
                          (reduce-kv
                           (fn [acc k v]
                             (cond-> acc (= "id" (name k)) (assoc :db/id v))) m m))
                        data))

  (def data100k-ids (add-ids data100k))
  (def people50k-ids (add-ids people50k))

  (def ds-100k (d/db-with (d/empty-db) data100k-ids))
  (def ds-50k (d/db-with (d/empty-db) people50k-ids))

  (def dx-100k (dx/create-dx data100k))
  (def dx-50k (dx/create-dx people50k))

  (def rd-100k (doto (rd/create-conn schema) (rd/transact! data100k-ids)))
  (def rd-50k (doto (rd/create-conn schema) (rd/transact! people50k-ids)))

  (count data100k))

(do
  (defn datascript-add-1 [data]
    (enc/qb 1
            (reduce
             (fn [db p]
               (-> db
                   (d/db-with [[:db/add (:db/id p) :name (:name p)]])
                   (d/db-with [[:db/add (:db/id p) :last-name (:last-name p)]])
                   (d/db-with [[:db/add (:db/id p) :age (:age p)]])
                   (d/db-with [[:db/add (:db/id p) :salary (:salary p)]])))
             (d/empty-db schema)
             data)))

  (defn doxa-add-1 [data]
    (enc/qb 1
            (reduce
             (fn [db p]
               (dx/commit db [[:dx/put p]]))
             {}
             data)))

  (defn re-db-add-1 [data]
    (enc/qb 1
            (reduce
             (fn [conn p] (doto conn (rd/transact! [p])))

             (rd/create-conn schema)
             data)))

  ;; result in ms
  (prn :people50k [:ds (datascript-add-1 people50k)
                   :doxa (doxa-add-1 people50k)
                   :re-db (re-db-add-1 people50k)]))
;; clj => [264 261]
(comment
  (defn datascript-add-all []
    (enc/qb 1
            (d/db-with (d/empty-db schema) people50k)))

  (defn doxa-add-all []
    (enc/qb 1
            (->> (into []
                       (map (fn [p] [:dx/put p]))
                       people50k)
                 (dx/commit {}))))

  (defn re-db-add-all []
    (enc/qb 1
            (rd/transact! (rd/create-conn schema) people50k)))

  (prn :add-all [:ds (datascript-add-all)
                 :doxa (doxa-add-all)
                 :re-db (re-db-add-all)]))
;; clj => [1483.59 42.56]


(do

  (defn datascript-q1 []
    (enc/qb 1
            (d/q '[:find ?e
                   :where [?e :name "Ivan"]]
                 ds-50k)))

  (defn dx-q1 []
    (enc/qb 1
            (dx/q [:find ?e
                   :where [?e :name "Ivan"]]
                  dx-50k)))

  (defn rd-q1 []
    (enc/qb 1
            (read/where rd-50k [[:name "Ivan"]])))


  (prn :q1 [:ds (datascript-q1)
            :doxa (dx-q1)
            :re-db (rd-q1)]))
;; cljs => [15 68 2]
;; clj  => [5.45 32.09]

(do
 (defn datascript-q2 []
   (enc/qb 1e1
           (d/q '[:find ?e ?a
                  :where [?e :name "Ivan"]
                  [?e :age ?a]]
                ds-50k)))

 (defn dx-q2 []
   (enc/qb 1e1
           (dx/q [:find [?e ?a]
                  :where [?e :name "Ivan"]
                  [?e :age ?a]]
                 dx-50k)))

 (defn rd-q2 []
   (enc/qb 1e1
           (->> (read/where rd-50k [[:name "Ivan"]
                                    :age])
                #_(mapv (juxt :db/id :age)))))

 (prn :q2 [:ds (datascript-q2)
           :doxa (dx-q2)
           :re-db (rd-q2)]))
;; cljs => [329 779 18]
;; clj  => [152.96 317.74]
(do
 (defn datascript-q3 []
   (enc/qb 1e1
           (d/q '[:find ?e ?a
                  :where [?e :name "Ivan"]
                  [?e :age ?a]
                  [?e :sex :male]]
                ds-50k)))

 (defn dx-q3 []
   (enc/qb 1e1
           (dx/q [:find [?e ?a]
                  :where [?e :name "Ivan"]
                  [?e :age ?a]
                  [?e :sex :male]]
                 dx-50k)))

 (defn rd-q3 []
   (enc/qb 1e1
           (read/where rd-50k [[:name "Ivan"]
                              :age
                              [:sex :male]])))

 (prn :q3 [:ds (datascript-q3)
           :doxa (dx-q3)
           :re-db (rd-q3)]))

(do
 (defn datascript-q4 []
   (enc/qb 1e1
           (d/q '[:find ?e ?l ?a
                  :where [?e :name "Ivan"]
                  [?e :last-name ?l]
                  [?e :age ?a]
                  [?e :sex :male]]
                ds-50k)))

 (defn dx-q4 []
   (enc/qb 1e1
           (doall
            (dx/q [:find [?e ?l ?a]
                   :where [?e :name "Ivan"]
                   [?e :last-name ?l]
                   [?e :age ?a]
                   [?e :sex :male]]
                  dx-50k))))

 (defn rd-q4 []
   (enc/qb 1e1
           (read/where rd-50k [[:name "Ivan"]
                              :age
                              :last-name
                              [:sex :male]])))

 (defn rd-q4-2 []
   ;; just return entities
   (enc/qb 1e1
           (read/where rd-50k [[:name "Ivan"]
                              :age
                              :last-name
                              [:sex :male]])))

 (prn :q4 [:ds (datascript-q4)
       :doxa (dx-q4)
       :re-db (rd-q4)]))
;; cljs => [  588    681]
;; clj  => [252.49 310.05]

(do
 (defn datascript-qpred1 []
   (enc/qb 1e1
           (d/q '[:find ?e ?s
                  :where [?e :salary ?s]
                  [(> ?s 50000)]]
                ds-50k)))

 (defn dx-qpred1 []
   (enc/qb 1e1
           (dx/q [:find ?e ?s
                  :where [?e :salary ?s]
                  [(> ?s 50000)]]
                 dx-50k)))

 (defn rd-qpred1 []
   (enc/qb 1e1
           (read/where rd-50k [(comp #(> % 50000) :salary)])))

 (defn rd-qpred1-juxt []
   (enc/qb 1e1
           (mapv (juxt :db/id :salary) (read/where rd-50k [(comp #(> % 50000) :salary)]))))

 (prn :qpred1
      [:ds (datascript-qpred1)
       :doxa (dx-qpred1)
       :re-db (rd-qpred1)
       :re-db-juxt (rd-qpred1-juxt)]))
;; cljs => [  321    959]
;; clj  => [259.34 384.9]

(do
 (defn datascript-pull1 []
   (enc/qb 1e3
           (d/pull ds-100k [:name] (rand-int 20000))))

 (defn dx-pull1 []
   (enc/qb 1e3
           (dx/pull dx-100k [:name] [:db/id (rand-int 20000)])))
 (defn rd-pull1 []
   (enc/qb 1e3
           (select-keys (read/get rd-100k (rand-int 10000)) [:name])))



 (prn :pull1 [:ds (datascript-pull1)
              :doxa (dx-pull1)
              :re-db (rd-pull1)]))
;; cljs => [   15    8]
;; clj  => [14.43 1.36]

(comment
 (defn datascript-pull2 []
   (enc/qb 1e3
           (d/pull ds-100k ['*] (rand-int 20000))))

 (defn dx-pull2 []
   (enc/qb 1e3
           (dx/pull dx-100k [:*] [:db/id (rand-int 20000)])))
 (defn rd-pull2 []
   (enc/qb 1e3
           (read/get rd-100k (rand-int 10000))))

 (prn :pull2 [:doxa (dx-pull2)
              :re-db (rd-pull2)
              :ds (datascript-pull2)]))
;; cljs => [   43   11]
;; clj  => [38.52 3.81]

(do
 (defn datascript-pull3 []
   (enc/qb 1e3
           (d/pull ds-100k [:name {:friend [:name]}] (rand-int 20000))))

 (defn dx-pull3 []
   (enc/qb 1e3
           (dx/pull dx-100k [:name {:friend [:name]}] [:db/id (rand-int 20000)])))
 (defn rd-pull3 []
   (enc/qb 1e3
           (read/touch
            (read/entity rd-100k (rand-int 10000))
            [:friend])))


 (prn :pull3 [:ds (datascript-pull3)
              :doxa (dx-pull3)
              :re-db (rd-pull3)]))
;; cljs => [   42   19]
;; clj  => [20.63 2.84]