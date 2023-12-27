(ns re-db.read-lite
  "experimental - entity with less granular reactivity (all-or-nothing)"
  (:refer-clojure :exclude [get])
  (:require [re-db.read :as read]
            [re-db.triplestore :as ts]
            [re-db.util :as u])
  #?(:cljs (:require-macros [re-db.read])))

;; EXPERIMENTAL -

(declare entity)

(u/support-clj-protocols
  (deftype Entity [conn ^:volatile-mutable e ^:volatile-mutable e-resolved? meta]
    #?@(:cljs [IPrintWithWriter
               (-pr-writer [o writer opts] (-write writer (str "#re-db/entity[" e "]")))])
    read/IEntity
    (conn [this] conn)
    (db [this] (ts/db conn))
    IMeta
    (-meta [this] meta)
    IWithMeta
    (-with-meta [this new-meta]
      (if (identical? new-meta meta)
        this
        (Entity. conn e e-resolved? new-meta)))
    IHash
    (-hash [this]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (hash [e (when e-resolved? (ts/eav db e))])))

    IEquiv
    (-equiv [this other]
      (and (instance? Entity other)
           (identical? conn (.-conn ^Entity other))
           (= (hash this) (hash other))
           (= (:db/id this) (:db/id other))))
    ILookup
    (-lookup [o a]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (when e-resolved?
          (read/-depend-on-triple! conn db nil e nil nil)
          (read/peek* conn db (ts/get-schema db a) entity e a))))
    (-lookup [o a nf]
      (case nf
        ::unwrapped
        (let [db (ts/db conn)]
          (re-db.read/-resolve-entity-e! conn db e e-resolved?)
          (when e-resolved?
            (read/-depend-on-triple! conn db nil e nil nil)
            (read/peek* conn db (ts/get-schema db a) entity e a)))
        (if-some [v (clojure.core/get o a)] v nf)))
    IDeref
    (-deref [this]
      (let [db (ts/db conn)]
        (re-db.read/-resolve-entity-e! conn db e e-resolved?)
        (when e-resolved?
          (read/-depend-on-triple! conn db nil e nil nil)
          (ts/eav db e))))
    ISeqable
    (-seq [this] (seq @this))))

(defn entity [conn e]
  (let [db (ts/db conn)]
    (if-let [e (read/-resolve-e conn db e)]
      (->Entity conn e true nil)
      (->Entity conn e false nil))))
