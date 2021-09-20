(ns re-db.read
  (:refer-clojure :exclude [get get-in select-keys contains? peek])
  (:require [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reagent :as re-db.reagent]
            [re-db.util :refer [guard]]
            [reagent.core :as reagent]
            [clojure.core :as core]
            [clojure.set :as set]))

(defn resolve-id
  "Returns id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes.
  The 3-arity version is for known lookup refs, and does not check for uniqueness."
  [conn e]
  (when ^boolean (vector? e)
    (re-db.reagent/log-read! conn :_av e))
  (db/resolve-e e @conn))

(defn contains?
  "Returns true if entity with given id exists in db."
  [conn e]
  (let [e (resolve-id conn e)]
    (when (some? e) (re-db.reagent/log-read! conn :e__ e))
    (core/contains? (core/get @conn :eav) e)))

(defn peek
  "Get attribute in entity with given id."
  ([conn e]
   (let [db @conn]
     (when-some [e (db/resolve-e e db)]
       (fast/get-in db [:eav e]))))
  ([conn e attr]
   (core/get (peek conn e) attr))
  ([conn e attr not-found]
   (core/get (peek conn e) attr not-found)))

(defn get
  "Get attribute in entity with given id."
  ([conn e]
   (when-some [id (resolve-id conn e)]
     (re-db.reagent/log-read! conn :e__ id)
     (fast/get-in @conn [:eav id])))
  ([conn e attr]
   (get conn e attr nil))
  ([conn e attr not-found]
   (if-some [id (resolve-id conn e)]
     (do (re-db.reagent/log-read! conn :ea_ [id attr])
         (core/get (fast/get-in @conn [:eav id]) attr not-found))
     not-found)))

(defn get-in
  "Get-in the entity with given id."
  ([conn ks]
   (let [[e attr & ks] ks]
     (when-some [id (resolve-id conn e)]
       (re-db.reagent/log-read! conn :ea_ [id attr])
       (-> (fast/get-in @conn [:eav id attr])
           (core/get-in ks)))))
  ([conn e ks]
   (get-in conn e ks nil))
  ([conn e ks not-found]
   (if-some [e (resolve-id conn e)]
     (do (re-db.reagent/log-read! conn :ea_ [e (first ks)])
         (-> (fast/get-in @conn [:eav e])
             (core/get-in ks not-found)))
     not-found)))

(defn select-keys
  "Select keys from entity of id"
  [conn e ks]
  (when-let [e (resolve-id conn e)]
    (let [m (fast/get-in @conn [:eav e])]
      (reduce (fn [out a]
                (re-db.reagent/log-read! conn :ea_ [e a])
                (if-some [v (m a)]
                  (conj out v)
                  out))
              {}
              ks))))

;; attribute reversal

(defn reverse-attr? [a]
  (= "_" (.charAt (name a) 0)))

(defn reverse-attr* [attr]
  (keyword (namespace attr) (str "_" (name attr))))

(fast/defmemo-1 reverse-attr reverse-attr*)

(defn forward-attr* [attr]
  (keyword (namespace attr) (subs (name attr) 1)))

(fast/defmemo-1 forward-attr forward-attr*)

(defn _av
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [conn [a v :as av]]
  (let [db @conn
        schema (db/get-schema db a)
        v (cond->> v (db/ref? schema) (resolve-id conn))]
    (re-db.reagent/log-read! conn :_av av)
    (if (db/indexed? schema)
      (or (fast/get-in db [:ave a v]) #{})
      (do
        (js/console.warn (str "Missing _av index on " a))
        (->> (:eav @conn)
             (reduce-kv
              (fn [out e m]
                (cond-> out
                        (= (m a) v)
                        (conj e)))
              #{}))))))

(defn _a_
  "Returns [e v] pairs for entities containing attribute (a).
   Optional `return-values?` param for returning only the entity-id."
  ([conn a] (_a_ conn a true))
  ([conn a return-values?]
   (re-db.reagent/log-read! conn :_a_ a)
   (let [db @conn]
     (if (db/_a_? (db/get-schema db a))
       (fast/get-in db [:_a_ a])
       (do
         (js/console.warn (str "Missing _a_ index on " a))
         (->> (:eav db)
              (reduce-kv
               (if return-values?
                 (fn [out e m] (if-some [v (m a)]
                                 (conj out [e v])
                                 out))
                 (fn [out e m] (if (some? (m a))
                                 (conj out e)
                                 out)))
               #{})))))))

(defn ___fn
  "Return entities for which `f` returns truthy"
  ([conn f] (___fn conn f true))
  ([conn f ^boolean return-entity?]
   (->> (:eav @conn)
        (reduce-kv
         (if return-entity?
           (fn [s e m] (cond-> s (f m) (conj m)))
           (fn [s e m] (cond-> s (f m) (conj e))))
         #{}))))

(declare entity Entity)

(defn touch*
  "Add reverse references to entity map"
  ([conn {v :db/id :as m}]
   (reduce-kv
    (fn [m a e] (assoc m (reverse-attr a) e))
    m
    (fast/get-in @conn [:vae v]))))

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
                  attr-schema (db/get-schema db a)
                  is-many (db/many? attr-schema)
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
  ([^Entity entity]
   (let [conn (.-conn entity)
         m @entity
         db @conn]
     (let [with-reverse-refs
           (reduce-kv
            (fn [m a e] (assoc m (reverse-attr a) (entity conn e)))
            m
            (fast/get-in db [:vae (:db/id m)]))]
       (reduce-kv (fn [m a v]
                    (let [attr-schema (db/get-schema db a)]
                      (if (db/ref? attr-schema)
                        (if (db/many? attr-schema)
                          (mapv #(entity conn %) v)
                          (assoc m a (entity conn v)))
                        m))) with-reverse-refs m))))
  ([^Entity entity pull]
   (pull* @entity pull @(.-conn entity) #{})))

(defn- ids-where-1 [conn q]
  (cond (vector? q) (_av conn q)
        (fn? q) (___fn conn q false)
        (keyword? q) (_a_ conn q false)
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
  (map #(entity conn %) (ids-where conn qs)))

(defn -resolve-id! [^Entity entity !db id]
  ;; entity ids are late-binding, you can pass a lookup ref for an entity that isn't yet in the db.
  (if (.-id-resolved? entity)
    id
    (if-some [id (resolve-id !db id)]
      (do (set! (.-id-resolved? entity) true)
          (set! (.-id entity) id)
          id)
      id)))

(deftype Entity [conn ^:volatile-mutable id ^:volatile-mutable ^boolean id-resolved?]
  ISeqable
  (-seq [this] (seq @this))
  IDeref
  (-deref [this]
    (let [id (-resolve-id! this conn id)]
      (re-db.reagent/log-read! conn :e__ id)
      (fast/get-in @conn [:eav id])))
  ILookup
  (-lookup [this a]
    (let [id (-resolve-id! this conn id)
          is-reverse (reverse-attr? a)
          a (cond-> a is-reverse forward-attr)
          db @conn
          schema (-> db (core/get :schema) (core/get a))]
      (if is-reverse
        (do
          (re-db.reagent/log-read! conn :_av [a id])
          (mapv #(entity conn %) (fast/get-in db [:vae id a])))
        (let [val (fast/get-in @conn [:eav id a])
              ref? (db/ref? schema)
              many? (db/many? schema)]
          (re-db.reagent/log-read! conn :ea_ [id a])
          (if ref?
            (if many?
              (mapv #(entity conn %) val)
              (some->> val (entity conn)))
            val)))))
  (-lookup [o attr not-found]
    (if-some [val (-lookup o attr)]
      val
      not-found)))

(defn entity [conn id]
  (Entity. conn (resolve-id conn id) false))

(defn create-conn [schema]
  (doto (db/create-conn schema)
    (db/listen! ::read re-db.reagent/invalidate-datoms!)))

(defn listen
  ([conn callback]
   (db/listen! conn (str (random-uuid)) callback))
  ([conn patterns callback]
   (let [initialized? (volatile! false)
         rxn (reagent/track!
              (fn []
                (reduce-kv (fn [_ pattern values]
                             (doseq [v values]
                               (re-db.reagent/log-read! conn pattern v))) nil patterns)
                (when @initialized? (callback conn))
                (vreset! initialized? true)
                nil))]
     #(reagent/dispose! rxn))))