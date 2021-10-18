(ns re-db.read
  (:refer-clojure :exclude [get contains? peek])
  (:require [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reagent :as re-db.reagent :refer [logged-read* logged-read!]]
            [re-db.util :refer [guard]]
            [re-db.schema :as schema]
            [reagent.core :as reagent]
            [clojure.core :as core]
            [clojure.set :as set]))

(def auto-index? true)

(js/console.info "re-db/auto-index:" auto-index?)

;; tracked index lookups

(defn -av_ [conn a v]
  (logged-read! conn nil a v
    (fast/get-in @conn [:ave a v])))

(defn -va_ [conn v a]
  (logged-read! conn nil a v
    (fast/get-in @conn [:vae v a])))

(defn -ae [conn a]
  (logged-read! conn nil a nil
    (fast/get-in @conn [:ae a])))

(defn -e__ [conn e]
  (logged-read! conn e nil nil
    (fast/get-in @conn [:eav e])))

(defn -ea_ [conn e a]
  (logged-read! conn e a nil
    (fast/get-in @conn [:eav e a])))

(defn -v__ [conn v]
  (logged-read! conn nil nil v
    (fast/get-in @conn [:vae v])))

;; lookup refs

(defn resolve-lookup-ref [conn [a v]]
  (assert (db/unique? (db/get-schema @conn a)))
  (if (vector? v)                                           ;; nested lookup ref
    (resolve-lookup-ref conn [a (resolve-lookup-ref conn v)])
    (when v
      (first (-av_ conn a v)))))

(defn resolve-e
  "Returns id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [conn e]
  (if (vector? e)
    (resolve-lookup-ref conn e)
    e))

;; functional lookup api

(defn get
  "Read entity or attribute reactively"
  ([conn e]
   (some->> (resolve-e conn e)
            (-e__ conn)))
  ([conn e attr]
   (get conn e attr nil))
  ([conn e attr not-found]
   (if-some [id (resolve-e conn e)]
     (or (-ea_ conn id attr) not-found)
     not-found)))

;; non-reactive alternative

(defn peek
  "Read entity or attribute without reactivity"
  ([conn e]
   (let [db @conn]
     (when-some [e (db/resolve-e e db)]
       (fast/get-in db [:eav e]))))
  ([conn e attr]
   (core/get (peek conn e) attr))
  ([conn e attr not-found]
   (core/get (peek conn e) attr not-found)))

;; attribute reversal

(defn reverse-attr? [a]
  (= "_" (.charAt (name a) 0)))

(defn reverse-attr* [attr]
  (keyword (namespace attr) (str "_" (name attr))))

(fast/defmemo-1 reverse-attr reverse-attr*)

(defn forward-attr* [attr]
  (keyword (namespace attr) (subs (name attr) 1)))

(fast/defmemo-1 forward-attr forward-attr*)

;; higher-level index lookups that resolve based on schema
;; and provide non-indexed backoffs

(def warned (volatile! #{}))
(defn warn! [index a]
  (when-not (@warned [index a])
    (vswap! warned conj [index a])
    (js/console.warn (str "Missing " index " on " a ". "
                          (case index :ave schema/ave
                                      :ae schema/ae)))))

(defn av_
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [conn [a v]]
  (let [db @conn
        a-schema (db/get-schema db a)
        v (cond->> v (db/ref? a-schema) (resolve-e conn))]
    (or (-av_ conn a v)
        (if (db/ave? a-schema)
          #{}
          (do
            (warn! :ave a)
            (if auto-index?
              (do
                (swap! conn db/add-missing-index a :ave)
                (recur conn [a v]))
              (->> (:eav db)
                   (reduce-kv
                    (fn [out e m]
                      (cond-> out
                              (= (m a) v)
                              (conj e)))
                    #{}))))))))

(defn _a_
  "Returns [e v] pairs for entities containing attribute (a).
   Optional `return-values?` param for returning only the entity-id."
  [conn a]
  (or (-ae conn a)
      (let [db @conn]
        (if (db/ae? (db/get-schema db a))
          #{}
          (do
            (warn! :ae a)
            (if auto-index?
              (do
                (swap! conn db/add-missing-index a :ae)
                (recur conn a))
              (->> (:eav db)
                   (reduce-kv
                    (fn [out e m] (cond-> out
                                          (some? (m a)) (conj e)))
                    #{}))))))))

(declare entity Entity)

(defn touch*
  "Add reverse references to entity map"
  ([conn {v :db/id :as m}]
   (reduce-kv
    (fn [m a e]
      (assoc m (reverse-attr a) e))
    m
    ;; TODO - support this pattern
    (-v__ conn v))))

(defn- pull* [{:as m e :db/id} pullv db found]
  (when m
    (->> pullv
         (reduce-kv
          (fn pull [m i pullexpr]
            (let [[a recurse] (if (keyword? pullexpr)
                                [pullexpr false]
                                (first pullexpr))
                  recursions (if (zero? recurse) false recurse)
                  _ (assert (or (core/contains? #{false :...} recurse) (number? recurse))
                            (str "Recursion parameter must be a number or :..., not " recurse))
                  set-a a
                  is-reverse? (reverse-attr? a)
                  a (cond-> a is-reverse? forward-attr)
                  a-schema (db/get-schema db a)
                  is-many (db/many? a-schema)
                  ;; ids of related entities
                  ids (if is-reverse?
                       (fast/get-in db [:vae e a])
                       (m a))
                  entities (when (seq ids)
                             (if recursions
                               (let [found (conj found e)
                                     pullv (if (number? recursions)
                                             ;; decrement recurse parameter
                                             (update-in pullv [i a] dec)
                                             pullv)]
                                 (into #{}
                                       (keep #(if (and (= :... recursions) (found %))
                                                %
                                                (some-> (db/get-entity db %)
                                                        (pull* pullv db found))))
                                       ids))
                               (into #{} (keep #(db/get-entity db %)) ids)))]
              (cond-> m
                      (seq entities)
                      (assoc set-a (cond-> entities (not is-many) first)))))
          m))))

(defn touch
  "Returns entity as map, following relationships specified
   in pull expression. (Entire entities are always returned,
   pull only opts-in to following a relationship)"
  ([^Entity this]
   (let [conn (.-conn this)
         m @this
         db @conn]
     (let [with-reverse-refs
           (reduce-kv
            (fn [m a e] (assoc m (reverse-attr a) (entity conn e)))
            m
            (-v__ conn (:db/id m)))]
       (reduce-kv (fn [m a v]
                    (let [a-schema (db/get-schema db a)]
                      (if (db/ref? a-schema)
                        (if (db/many? a-schema)
                          (mapv #(entity conn %) v)
                          (assoc m a (entity conn v)))
                        m))) with-reverse-refs m))))
  ([^Entity this pull]
   (pull* @this pull @(.-conn this) #{})))

(defn- ids-where-1 [conn q]
  (cond (vector? q) (av_ conn q)
        (keyword? q) (_a_ conn q)
        (fn? q) (into #{} (filter q) (vals (:eav @conn)))
        :else (throw (js/Error. (str "Invalid where-1 clause:" q)))))

(defn ids-where
  [conn [q & qs]]
  (reduce (fn [out q]
            (if (seq out)
              (set/intersection out (ids-where-1 conn q))
              (reduced out)))
          (ids-where-1 conn q)
          qs))

(defn where
  [conn qs]
  (mapv #(entity conn %) (ids-where conn qs)))

(defn -resolve-e! [^Entity entity conn id]
  ;; entity ids are late-binding, you can pass a lookup ref for an entity that isn't yet in the db.
  (if (.-id-resolved? entity)
    id
    (if-some [id (resolve-e conn id)]
      (do (set! (.-id-resolved? entity) true)
          (set! (.-id entity) id)
          id)
      id)))

(deftype Entity [conn ^:volatile-mutable e ^:volatile-mutable ^boolean id-resolved? meta]
  IMeta
  (-meta [this] meta)
  IWithMeta
  (-with-meta [this new-meta]
    (if (identical? new-meta meta)
      this
      (Entity. conn e id-resolved? new-meta)))
  IHash
  (-hash [this]
    (-hash (let [e (-resolve-e! this conn e)]
             [e (fast/get-in @conn [:eav e])])))
  IEquiv
  (-equiv [this other]
    (and (instance? Entity other)
         (identical? conn (.-conn ^Entity other))
         (= (-resolve-e! this conn e)
            (-resolve-e! other conn e))))
  ISeqable
  (-seq [this] (seq @this))
  IDeref
  (-deref [this]
    (-e__ conn (-resolve-e! this conn e)))
  ILookup
  (-lookup [this a]
    (let [e (-resolve-e! this conn e)
          is-reverse (reverse-attr? a)
          a (cond-> a is-reverse forward-attr)
          db @conn
          a-schema (db/get-schema db a)
          is-ref? (db/ref? a-schema)]
      (if is-reverse
        (do
          (assert is-ref?)
          (mapv #(entity conn %) (-va_ conn e a)))
        (let [v (-ea_ conn e a)
              is-many? (db/many? a-schema)]
          (if is-ref?
            (if is-many?
              (mapv #(entity conn %) v)
              (some->> v (entity conn)))
            v)))))
  (-lookup [this a not-found]
    (if-some [val (-lookup this a)]
      val
      not-found)))

(defn entity [conn id]
  (Entity. conn (resolve-e conn id) false nil))

(defn listen-conn [conn]
  (doto conn (db/listen! ::read re-db.reagent/invalidate-readers!)))

(defn create-conn [schema]
  (listen-conn (db/create-conn schema)))

(defn listen
  ([conn callback]
   (db/listen! conn (str (random-uuid)) callback))
  ([conn patterns callback]
   (let [initialized? (volatile! false)
         rxn (reagent/track!
              (fn []
                (doseq [[e a v] patterns]
                  (logged-read* conn e a v (constantly nil)))
                (when @initialized? (callback conn))
                (vreset! initialized? true)
                nil))]
     #(reagent/dispose! rxn))))