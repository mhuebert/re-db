(ns re-db.read
  (:refer-clojure :exclude [get contains? peek])
  (:require [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reagent :as re-db.reagent :refer [log-read!]]
            [re-db.util :refer [guard]]
            [reagent.core :as reagent]
            [clojure.core :as core]
            [clojure.set :as set]))

;; tracked index lookups

(defn -av_ [conn [a v :as av]]
  (log-read! conn :_av av)
  (fast/get-in @conn [:ave a v]))

(defn -va_ [conn [v a]]
  (log-read! conn :_av [a v])
  (fast/get-in @conn [:vae v a]))

(defn -_a_ [conn a]
  (log-read! conn :ae a)
  (fast/get-in @conn [:ae a]))

(defn -e__ [conn e]
  (log-read! conn :e__ e)
  (fast/get-in @conn [:eav e]))

(defn -ea_ [conn [e a :as ea]]
  (log-read! conn :ea_ ea)
  (fast/get-in @conn [:eav e a]))

;; lookup refs

(defn resolve-lookup-ref [conn [a v :as e]]
  (if (vector? v)                                           ;; nested lookup ref
    (resolve-lookup-ref conn [a (resolve-lookup-ref conn v)])
    (when v
      (first (-av_ conn e)))))

(defn resolve-e
  "Returns id, resolving lookup refs (vectors of the form `[attribute value]`) to ids.
  Lookup refs are only supported for indexed attributes."
  [conn e]
  (cond->> e
           (vector? e)
           (resolve-lookup-ref conn)))

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
     (or (-ea_ conn [id attr]) not-found)
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

(defn av_
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [conn [a v]]
  (let [db @conn
        schema (db/get-schema db a)
        v (cond->> v (db/ref? schema) (resolve-e conn))]
    (or (-av_ conn [a v])
        (if (db/indexed? schema)
          #{}
          (do
            #_(js/console.warn (str "Missing :ave index on " a))
            (->> (:eav db)
                 (reduce-kv
                  (fn [out e m]
                    (cond-> out
                            (= (m a) v)
                            (conj e)))
                  #{})))))))

(defn _a_
  "Returns [e v] pairs for entities containing attribute (a).
   Optional `return-values?` param for returning only the entity-id."
  [conn a]
  (or (-_a_ conn a)
      (let [db @conn]
        (if (db/ae? (db/get-schema db a))
          #{}
          (do
            #_(js/console.warn (str "Missing _a_ index on " a))
            (->> (:eav db)
                 (reduce-kv
                  (fn [out e m] (cond-> out
                                        (some? (m a)) (conj e)))
                  #{})))))))

(declare entity Entity)

(defn touch*
  "Add reverse references to entity map"
  ([conn {v :db/id :as m}]
   (reduce-kv
    (fn [m a e]
      (log-read! conn :_av [a e])                           ;; TODO - test
      (assoc m (reverse-attr a) e))
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
  (cond (vector? q) (av_ conn q)
        (keyword? q) (_a_ conn q)
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

(deftype Entity [conn ^:volatile-mutable e ^:volatile-mutable ^boolean id-resolved?]
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
          attr-schema (db/get-schema db a)
          is-ref? (db/ref? attr-schema)]
      (if is-reverse
        (do
          (assert is-ref?)
          (mapv #(entity conn %) (-va_ conn [e a])))
        (let [v (-ea_ conn [e a])
              is-many? (db/many? attr-schema)]
          (if is-ref?
            (if is-many?
              (mapv #(entity conn %) v)
              (some->> v (entity conn)))
            v)))))
  (-lookup [o attr not-found]
    (if-some [val (-lookup o attr)]
      val
      not-found)))

(defn entity [conn id]
  (Entity. conn (resolve-e conn id) false))

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
                               (log-read! conn pattern v))) nil patterns)
                (when @initialized? (callback conn))
                (vreset! initialized? true)
                nil))]
     #(reagent/dispose! rxn))))