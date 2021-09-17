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

(defn reverse-attr* [attr]
  (keyword (namespace attr) (str "_" (name attr))))

(fast/defmemo-1 reverse-attr reverse-attr*)

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

(defn touch
  "Add reverse references to entity"
  ([conn m] (touch conn m true))
  ([conn {v :db/id :as m} wrap-entity?]
   (reduce-kv
    (if wrap-entity?
      (fn [m a e] (assoc m (reverse-attr a) (entity conn e)))
      (fn [m a e] (assoc m (reverse-attr a) e)))
    m
    (fast/get-in @conn [:vae v]))))

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
  (-lookup [this attr]
    (let [id (-resolve-id! this conn id)
          reverse-attr? (= "_" (.charAt (name attr) 0))
          attr (if reverse-attr?
                 (keyword (namespace attr) (subs (name attr) 1))
                 attr)
          db @conn
          schema (-> db (core/get :schema) (core/get attr))]
      (if reverse-attr?
        (do
          (re-db.reagent/log-read! conn :_av [attr id])
          (mapv #(entity conn %) (fast/get-in db [:vae id attr])))
        (let [val (fast/get-in @conn [:eav id attr])
              ref? (db/ref? schema)
              many? (db/many? schema)]
          (re-db.reagent/log-read! conn :ea_ [id attr])
          (if ref?
            (if many?
              (mapv #(entity conn %) val)
              (entity conn val))
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