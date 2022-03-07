(ns re-db.read
  (:refer-clojure :exclude [get peek])
  (:require [re-db.core :as db]
            [re-db.fast :as fast]
            [re-db.reagent :as re-db.reagent :refer [read-index!]]
            [re-db.util :as util :refer [guard]]
            [re-db.schema :as schema]
            #?(:cljs [reagent.core :as reagent])
            [clojure.core :as core]
            [clojure.set :as set]))

(def auto-index? true)

(#?(:cljs js/console.info :clj prn) "re-db/auto-index:" auto-index?)

;; lookup refs

(defn resolve-lookup-ref [conn [a v]]
  (assert (:unique (db/get-schema @conn a)))
  (if (vector? v)                                           ;; nested lookup ref
    (resolve-lookup-ref conn [a (resolve-lookup-ref conn v)])
    (when v
      (first (read-index! conn :ave a v)))))

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
            (read-index! conn :eav)))
  ([conn e attr]
   (get conn e attr nil))
  ([conn e attr not-found]
   (if-some [id (resolve-e conn e)]
     (or (read-index! conn :eav id attr) not-found)
     not-found)))

;; non-reactive alternative

(defn peek
  "Read entity or attribute without reactivity"
  ([conn e]
   (let [db @conn]
     (when-some [e (db/resolve-e e db)]
       (fast/gets db :eav e))))
  ([conn e attr]
   (core/get (peek conn e) attr))
  ([conn e attr not-found]
   (core/get (peek conn e) attr not-found)))

;; attribute reversal

(defn reverse-attr? [a]
  (= \_ (.charAt (name a) 0)))
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
    (#?(:cljs js/console.warn
        :clj prn) (str "Missing " index " on " a ". "
                       (case index :ave schema/ave
                                   :ae schema/ae)))))

(defn av_
  "Returns entity-ids for entities where attribute (a) equals value (v)"
  [conn [a v]]
  (let [db @conn
        a-schema (db/get-schema db a)
        v (cond->> v ^boolean (:ref a-schema) (resolve-e conn))]
    (if ^boolean (:ave a-schema)
      (read-index! conn :ave a v)
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
                #{})))))))

(defn _a_
  "Returns [e v] pairs for entities containing attribute (a).
   Optional `return-values?` param for returning only the entity-id."
  [conn a]
  (let [db @conn
        a-schema (db/get-schema db a)]
    (if ^boolean (:ae a-schema)
      (read-index! conn :ae a)
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
                #{})))))))

(declare entity)

(defn touch*
  "Add reverse references to entity map"
  ([conn {v :db/id :as m}]
   (reduce-kv
    (fn [m a e]
      (assoc m (reverse-attr a) e))
    m
    ;; TODO - support this pattern
    (read-index! conn :vae v))))

(defn- pull* [{:as m e :db/id} pullv db found]
  (when m

    (reduce-kv
     (fn pull [m i pullexpr]
       (let [[a recurse] (if (keyword? pullexpr)
                           [pullexpr false]
                           (first pullexpr))
             recursions (if (= 0 recurse) false recurse)
             _ (assert (or (contains? #{false :...} recurse) (number? recurse))
                       (str "Recursion parameter must be a number or :..., not " recurse))
             is-reverse? (reverse-attr? a)
             forward-a (cond-> a is-reverse? forward-attr)
             ^db/Schema a-schema (db/get-schema db forward-a)
             is-many ^boolean (:many a-schema)
             ;; ids of related entities
             ids (if is-reverse?
                   (fast/gets db :vae e forward-a)
                   (cond-> (m forward-a) (not is-many) list))
             entities (when (seq ids)
                        (if recursions
                          (let [found (conj found e)
                                pullv (if (number? recursions)
                                        ;; decrement recurse parameter
                                        (update-in pullv [i forward-a] dec)
                                        pullv)]
                            (into #{}
                                  (keep #(if (and (= :... recursions) (found %))
                                           %
                                           (some-> (db/get-entity db %)
                                                   (pull* pullv db found))))
                                  ids))
                          (into #{} (keep #(db/get-entity db %)) ids)))
             v (cond-> entities (not is-many) first)]
         (cond-> m
                 (seq entities)
                 (assoc a v))))
     m
     pullv)))

(defn pull
  "Returns entity as map, as well as linked entities specified in `pull`.

  (pull conn 1 [:children]) =>
    {:db/id 1
     :children [{:db/id 2}
                {:db/id 3}]}"
  [conn id pull]
  (pull* (get conn id) pull @conn #{}))

(defn- ids-where-1 [conn q]
  (cond (vector? q) (av_ conn q)
        (keyword? q) (_a_ conn q)
        (fn? q) (into #{} (filter q) (vals (:eav @conn)))
        :else (throw (#?(:cljs js/Error. :clj Exception.) (str "Invalid where-1 clause:" q)))))

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

(defprotocol IEntity
  (resolved? [entity])
  (resolve! [entity id]))

(defn -resolve-e! [entity conn id]
  ;; entity ids are late-binding, you can pass a lookup ref for an entity that isn't yet in the db.
  (if (resolved? entity)
    id
    (if-some [id (resolve-e conn id)]
      (do (resolve! entity id)
          id)
      id)))

(util/support-clj-protocols
 (deftype Entity [conn ^:volatile-mutable e ^:volatile-mutable id-resolved? meta]
   IEntity
   (resolved? ^boolean [this] id-resolved?)
   (resolve! [this id]
     (set! e id)
     (set! id-resolved? true)
    this)
   IMeta
   (-meta [this] meta)
   IWithMeta
   (-with-meta [this new-meta]
     (if (identical? new-meta meta)
       this
       (Entity. conn e id-resolved? new-meta)))
   IHash
   (-hash [this]
     (hash (let [e (-resolve-e! this conn e)]
             [e (fast/gets @conn :eav e)])))

   IEquiv
   (-equiv [this other]
     (and (instance? Entity other)
          (identical? conn (.-conn ^Entity other))
          (= (hash this) (hash other))
          (= (-resolve-e! this conn e)
             (-resolve-e! other conn (.-e ^Entity other)))))
   ISeqable
   (-seq [this] (seq @this))
   IDeref
   (-deref [this]
     (read-index! conn :eav (-resolve-e! this conn e)))
   ILookup
   (-lookup [this a]
     (let [e (-resolve-e! this conn e)
           is-reverse (reverse-attr? a)
           a (cond-> a is-reverse forward-attr)
           db @conn
           a-schema (db/get-schema db a)
           is-ref? ^boolean (:ref a-schema)]

       (if is-reverse
         (do
           (assert is-ref?)
           (mapv #(entity conn %) (read-index! conn :vae e a)))
         (let [v (read-index! conn :eav e a)
               is-many? ^boolean (:many a-schema)]
           (if is-ref?
             (if is-many?
               (mapv #(entity conn %) v)
               (some->> v (entity conn)))
             v)))))
   (-lookup [this a not-found]
     (if-some [val (get this a)]
       val
       not-found))))

(defn touch
  "Returns entity as map, including reverse refs. Pass `entity-refs?` to
   wrap refs with entity api."
  ([entity] (touch entity false))
  ([entity* entity-refs?]
   (let [conn (.-conn ^Entity entity*)]
     (if entity-refs?
       (let [m @entity*
             db @conn]
         (let [with-reverse-refs
               (reduce-kv
                (fn [m a e] (assoc m (reverse-attr a) (mapv #(entity conn %) e)))
                m
                (read-index! conn :vae (:db/id m)))]
           (reduce-kv (fn [m a v]
                        (let [a-schema (db/get-schema db a)]
                          (if ^boolean (:ref a-schema)
                            (if ^boolean (:many a-schema)
                              (mapv #(entity conn %) v)
                              (assoc m a (entity conn v)))
                            m))) with-reverse-refs m)))
       (let [{:keys [db/id] :as m} @entity*]
         (reduce-kv
          (fn [m a e] (assoc m (reverse-attr a) e))
          m
          (read-index! conn :vae id)))))))

(defn entity [conn id]
  (Entity. conn (resolve-e conn id) false nil))

(defn listen-conn [conn]
  #?(:cljs (doto conn (db/listen! ::read re-db.reagent/invalidate-readers!))
     :clj conn))

(def create-conn (comp listen-conn db/create-conn))

(defn listen
  [conn callback]
  #?(:cljs
     (db/listen! conn (str (random-uuid)) callback)))