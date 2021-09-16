(ns re-db.read
  (:refer-clojure :exclude [get get-in select-keys contains? peek])
  (:require [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reagent :as re-db.reagent]
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

(defn touch
  "Add refs to entity"
  [conn {e :db/id :as entity}]
  (reduce-kv
    (fn [m attr es]
      (assoc m (reverse-attr attr) es))
    entity
    (fast/get-in @conn [:vae e])))


(defn entity-ids
  [conn qs]
  (assert (satisfies? IDeref conn))
  (->> qs
       (mapv (fn [q]
               (set (cond (fn? q)
                          (reduce-kv (fn [s id entity] (if ^boolean (q entity) (conj s id) s)) #{} (core/get @conn :eav))

                          (keyword? q)
                          (do (re-db.reagent/log-read! conn :_a_ q)
                              (reduce-kv (fn [s id entity] (if ^boolean (core/contains? entity q) (conj s id) s)) #{} (core/get @conn :eav)))

                          :else
                          (let [[attr v] q
                                db-snap @conn
                                schema (db/get-schema db-snap attr)
                                v (cond->> v (db/ref? schema) (resolve-id conn))]
                            (re-db.reagent/log-read! conn :_av [attr v])
                            (if (db/indexed? schema)
                              (fast/get-in db-snap [:ave attr v])
                              (do
                                (js/console.warn (str "no index on " attr))
                                (entity-ids conn [#(= v (core/get % attr))]))))))))
       (apply set/intersection)))

(declare entity Entity)

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

(defn entities
  [conn qs]
  (map #(entity conn %) (entity-ids conn qs)))

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