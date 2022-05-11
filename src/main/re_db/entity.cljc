(ns re-db.entity
  (:require [re-db.protocols :as rp]
            [re-db.patterns :as patterns :refer [*conn*]]
            [re-db.util :as u])
  #?(:cljs (:require-macros [re-db.entity :refer [resolve-e!]])))

(defmacro resolve-e! [conn db e-sym e-resolved-sym]
  `(do (when-not ~e-resolved-sym
         (when-some [e# (patterns/resolve-e ~conn ~db ~e-sym)]
           (set! ~e-sym e#)
           (set! ~e-resolved-sym true)))
       ~e-sym))

(declare entity)

(defn get-db [conn db] (or db (rp/db conn)))

(u/support-clj-protocols
  (deftype Entity [conn db ^:volatile-mutable e ^:volatile-mutable e-resolved? get-v]
    IMeta
    (-meta [this] meta)
    IWithMeta
    (-with-meta [this new-meta]
      (if (identical? new-meta meta)
        this
        (Entity. conn db e e-resolved? get-v)))
    IHash
    (-hash [this]
      (resolve-e! conn (get-db conn db) e e-resolved?)
      (hash [e (rp/as-map (get-db conn db) e)]))

    IEquiv
    (-equiv [this other]
      (and (instance? Entity other)
           (identical? conn (.-conn ^Entity other))
           (= (hash this) (hash other))
           (= (:db/id this) (:db/id other))))
    ILookup
    (-lookup [o a]
      (resolve-e! conn (get-db conn db) e e-resolved?)
      (case a
        :db/id e
        (let [v (get-v a)
              is-reverse (u/reverse-attr? a)
              a (cond-> a is-reverse u/forward-attr)
              a-schema (rp/get-schema (get-db conn db) a)]
          (if is-reverse
            (do
              (patterns/depend-on-triple! nil a e) ;; [_ a v]
              (into #{} (map #(entity conn db %)) v))
            (do
              (patterns/depend-on-triple! e a nil) ;; [e a _]
              (if (rp/ref? (get-db conn db) a a-schema)
                (if (rp/many? (get-db conn db) a a-schema)
                  (into #{} (map #(entity conn db %)) v)
                  (entity conn db v))
                v))))))
    (-lookup [o k nf]
      (if-some [v (get o k)] v nf))
    IDeref
    (-deref [this]
      (patterns/depend-on-triple! e nil nil)
      (rp/as-map (get-db conn db) e))
    ISeqable
    (-seq [this] (seq @this))))

(defn entity
  ([conn db e]
   (let [e (:db/id e e)]
     (->Entity conn db e false (memoize #(rp/eav (get-db conn db) e %)))))
  ([conn e] (entity conn nil e)) ;; conn is specified - use it for db-value, late-bound
  ([e] (entity *conn* nil e))) ;; nothing is specified - use current *conn*