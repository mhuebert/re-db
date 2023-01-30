(ns re-db.reactive
  (:refer-clojure :exclude [atom peek])
  (:require [re-db.macros :as macros]
            [re-db.util :as util]
            [re-db.impl.hooks :as -hooks])
  #?(:cljs (:require-macros re-db.reactive)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocols

(defprotocol IReactiveFunction
  "Protocol for reactive values that have a compute function"
  (compute! [this] "compute value, reset state, notify dependents")
  (compute [this] "compute value")
  (set-stale! [this stale] "mark instance as stale, return this")
  (stale? [this] "returns true if the instance must be (re)computed before a valid value can be read")
  (computes? [this] "marker method - returns false for default impl"))

(defprotocol IReactiveValue
  "Protocol for reactive values that can be watched and have a lifecycle based on reference count"
  (get-watches [this])
  (set-watches! [this new-watches])
  (add-on-dispose! [this f])
  (dispose! [this])
  (detach! [this] "Detach from graph - computes immediately and remains active until explicitly disposed")
  (detached? [this]))

(defprotocol ITrackDerefs
  "for implicitly recording dependencies via `deref`"
  (get-derefs [this])
  (set-derefs! [this new-derefs]))

(def ^:dynamic *captured-derefs* nil)

(defprotocol IPeek
  "for reading reactive values without triggering a dependency relationship.
   Note: this does not trigger computation of disposed/stale sources."
  (peek [this]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default implementations

(extend-type #?(:clj Object :cljs object)
  IReactiveValue
  (get-watches [this] nil)
  (set-watches! [this new-watches] this)
  (add-on-dispose! [this f] this)
  (dispose! [this] this)
  (detach! [this] this)
  (detached? [this] true)

  IReactiveFunction
  (compute! [this] this)
  (compute [this] nil)
  (stale? [this] false)
  (set-stale! [this stale] this)
  (computes? [this] false)

  IPeek
  (peek [this] (macros/without-deref-capture @this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notify
;;
;; When a ref changes, notify dependents (watches) in topological order

(defn rdistinct
  "Returns v keeping the last occurrence of each element."
  [v]
  (into ()
        (distinct)
        (rseq v)))

(defn sorted-dependents
  ([root get-fn] (some-> (sorted-dependents #{} root get-fn) rdistinct))
  ([seen root get-dependents]
   (let [seen (conj seen root)
         depts (get-dependents root)]
     (reduce (fn [acc x]
               (if (seen x)
                 acc
                 (into acc (sorted-dependents seen x get-dependents))))
             (vec depts)
             depts))))

(def ^:dynamic *notifying* nil)

(defn- handle-change! [ref old-val new-val]
  (doseq [[k f] (get-watches ref)]
    (if (computes? k)
      (set-stale! k true)
      (vswap! *notifying* conj #(f k ref old-val new-val)))))

(defn notify-watches
  [ref old-val new-val]
  (when (seq (get-watches ref))
    (if *notifying*
      (handle-change! ref old-val new-val)
      (binding [*notifying* (volatile! [])]
        (handle-change! ref old-val new-val)
        (doseq [r (sorted-dependents ref (comp keys get-watches))
                :when (stale? r)
                :let [_ (set-stale! r false)
                      old-val (peek r)
                      new-val (compute r)]]
          (when (not= old-val new-val)
            (reset! r new-val)))
        (doseq [f @*notifying*] (f))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *owner*
  "Owner of the current reactive computation"
  nil)

;; for reagent compatibility
#?(:cljs (defonce get-reagent-context (constantly nil)))

(defn owner []
  #?(:clj  *owner*
     :cljs (or *owner* (get-reagent-context))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn become
  "Moves all watches from `old-ref` to a new ref, and calls `notify-watches` if the
   value has changed. The new ref is created via `init-fn` after the old ref is disposed."
  ;; https://gbracha.blogspot.com/2009/07/miracle-of-become.html
  [old-ref init-fn]
  (let [old-watches (get-watches old-ref)
        old-val (peek old-ref)]
    (dispose! old-ref)
    (let [to (init-fn old-val)]
      (doseq [[consumer f] old-watches]
        (add-watch to consumer f))
      (let [new-val @to]
        (when (not= old-val new-val)
          (notify-watches to old-val new-val)))
      to)))

(defmacro redef
  "Reactive `def` - value should be a reactive value. If name already exists, migrates watches to the new reactive value."
  ([name doc rx] `(redef ~(with-meta name {:doc doc}) ~rx))
  ([name rx]
   (let [rx-sym (gensym "rx")]
     `(do (declare ~name)
          (let [~rx-sym ~rx]
            (if (macros/present? ~name)
              ~(if (:ns &env)
                 `(set! ~name (become ~name (constantly ~rx-sym)))
                 `(do (become ~name (constantly (alter-var-root (var ~name) (constantly ~rx-sym))))
                      (var ~name))))
            (def ~name ~rx-sym))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deref tracking

#?(:cljs (defonce reagent-notify-deref-watcher! (fn [_])))

(defn collect-deref! [producer]
  #?(:cljs (reagent-notify-deref-watcher! producer))
  (some-> *captured-derefs* (vswap! conj producer)))

(defn handle-new-derefs! [consumer new-derefs]
  (let [derefs (get-derefs consumer)
        [added removed] (util/set-diff (util/guard derefs seq)
                                       (util/guard new-derefs seq))]
    (doseq [producer added] (add-watch producer consumer (fn [_ _ _ _] (compute! consumer))))
    (doseq [producer removed] (remove-watch producer consumer))
    (set-derefs! consumer new-derefs)
    consumer))

(defn dispose-derefs! [consumer] (handle-new-derefs! consumer nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initial values

(def init-meta {})
(def init-stale true)
(def init-watches {})
(def init-dispose-fns [])
(def init-detached false)
(def init-derefs #{})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reactive Atom - basic reactive value

(util/support-clj-protocols
  (deftype RAtom [^:volatile-mutable state
                  ^:volatile-mutable watches
                  ^:volatile-mutable dispose-fns
                  ^:volatile-mutable detached
                  ^:volatile-mutable meta]
    IReactiveValue
    (get-watches [this] watches)
    (set-watches! [this! new-watches] (set! watches new-watches))
    (detached? [this] detached)
    (detach! [this]
      (when-not detached
        (set! detached true)))
    (add-on-dispose! [this f]
      (macros/set-swap! dispose-fns conj f))
    (dispose! [this]
      (doseq [f dispose-fns] (f this))
      (set! dispose-fns init-dispose-fns))
    IMeta
    (-meta [this] meta)
    #?@(:clj [clojure.lang.IReference
              (alterMeta [this f args] (set! meta (apply f meta args)))
              (resetMeta [this new-meta] (set! meta new-meta))])
    IPeek
    (peek [this] state)
    IDeref
    (-deref [this]
      (collect-deref! this)
      state)
    IReset
    (-reset! [this new-val]
      (let [old-val state]
        (set! state new-val)
        (notify-watches this old-val new-val)
        new-val))
    ISwap
    (-swap! [this f] (reset! this (f state)))
    (-swap! [this f x] (reset! this (f state x)))
    (-swap! [this f x y] (reset! this (f state x y)))
    (-swap! [this f x y args] (reset! this (apply f state x y args)))
    IWatchable
    (-add-watch [this key f]
      (macros/set-swap! watches assoc key f)
      this)
    (-remove-watch [this key]
      (macros/set-swap! watches dissoc key)
      (when (and (empty? watches) (not detached))
        (dispose! this))
      this)))

(defn atom
  ([initial-value]
   (->RAtom initial-value
            init-watches
            init-dispose-fns
            init-detached
            init-meta))
  ([initial-value & {:keys [meta detached on-dispose]
                     :or {meta init-meta
                          detached init-detached}}]
   (->RAtom initial-value
            init-watches
            (cond-> init-dispose-fns
                    on-dispose
                    (conj on-dispose))
            detached
            meta)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reactions - computed reactive values

(defn error? [x]
  (instance? #?(:cljs js/Error :clj Exception) x))

(util/support-clj-protocols
  (deftype Reaction
    [^:volatile-mutable compute-fn
     ^:volatile-mutable state
     ^:volatile-mutable watches
     ^:volatile-mutable dispose-fns
     ^:volatile-mutable detached
     ^:volatile-mutable derefs
     ^:volatile-mutable hooks
     ^:volatile-mutable stale
     ^:volatile-mutable meta]
    IReactiveValue
    (get-watches [this] watches)
    (set-watches! [this new-watches] (set! watches new-watches))
    (detached? [this] detached)
    (detach! [this]
      (when-not detached
        (set! detached true)
        (compute! this))
      this)
    (add-on-dispose! [this f]
      (macros/set-swap! dispose-fns conj f))
    (dispose! [this]
      (dispose-derefs! this)
      (-hooks/dispose-hooks! this)
      (set! stale true)
      (doseq [f dispose-fns] (f this))
      (set! dispose-fns init-dispose-fns))
    IMeta
    (-meta [this] meta)
    #?@(:clj [clojure.lang.IReference
              (alterMeta [this f args] (set! meta (apply f meta args)))
              (resetMeta [this new-meta] (set! meta new-meta))])
    -hooks/IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))
    (get-hook [this i] (nth hooks i nil))
    (set-hook! [this i new-hook]
      (set! hooks (assoc hooks i new-hook))
      new-hook)

    IDeref
    (-deref [this]

      (when-not (identical? *owner* this)
        (collect-deref! this))

      (when stale (compute! this))

      (when (error? state) (throw state))

      state)

    IPeek
    (peek [this] state)
    IReset
    (-reset! [this new-val]
      (let [old-val state]
        (set! state new-val)
        (notify-watches this old-val new-val)
        new-val))
    ISwap
    (-swap! [this f] (reset! this (f state)))
    (-swap! [this f x] (reset! this (f state x)))
    (-swap! [this f x y] (reset! this (f state x y)))
    (-swap! [this f x y args] (reset! this (apply f state x y args)))
    IWatchable
    (-add-watch [this key compute-fn]

     ;; if the reaction is not yet initialized, do it here (before adding the watch,
     ;; so that the watch-fn is not called here)
      (when stale (compute! this))

      (macros/set-swap! watches assoc key compute-fn)
      this)
    (-remove-watch [this key]
      (macros/set-swap! watches dissoc key)
      (when (and (empty? watches) (not detached))
        (dispose! this))
      this)
    ITrackDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    IReactiveFunction
    (stale? [this] stale)
    (set-stale! [this is-stale] (set! stale is-stale))
    (compute [this]
      (macros/with-owner this
        (macros/with-hook-support!
         (macros/with-deref-capture! this
           (try (compute-fn)
                (catch #?(:cljs js/Error :clj Exception) e
                  e))))))
    (compute! [this]
      (set! stale false)
      (let [new-val (compute this)]
        (when (not= state new-val)
          (reset! this new-val))
        this))
    (computes? [this] true)))

(defn make-reaction
  ([compute-fn] (make-reaction nil compute-fn))
  ([{:keys [init
            meta
            on-dispose
            stale
            detached]
     :or {stale init-stale
          detached init-detached}}
    compute-fn]
   (cond-> (->Reaction compute-fn
                       init
                       init-watches
                       (cond-> init-dispose-fns
                               on-dispose (conj on-dispose))
                       detached
                       init-derefs
                       -hooks/init-hooks
                       stale
                       meta)
           detached compute!)))

(defn as-result
  "Returns a map containing :value or :error (if v is an exception)"
  [value]
  (if (error? value)
    {:error value}
    {:value value}))

(defn deref-result
  "Returns a map containing :value or :error, without throwing an exception."
  [rx]
  (when (stale? rx) (compute! rx))
  (as-result (peek rx)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (make-reaction {:stale false} (fn [])))

(comment
 ;; using a session
 (let [my-session (make-session)
       a (doto (atom nil)
           (add-on-dispose! (fn [_] (prn :disposed-a))))
       b (doto (atom nil)
           (add-on-dispose! (fn [_] (prn :disposed-b))))]
   (with-session my-session @a)
   (with-session my-session @b)
   (dispose! my-session)))

;; macro passthrough
(defmacro session [& body] `(macros/session ~@body))
(defmacro without-deref-capture [& body] `(macros/without-deref-capture ~@body))
(defmacro static [& body] `(macros/without-deref-capture ~@body))
(defmacro reaction [& body] `(macros/reaction ~@body))
(defmacro reaction! [& body] `(detach! (macros/reaction ~@body)))

#?(:clj
   (defmethod print-method re_db.reactive.Reaction
     [^re_db.reactive.Reaction rx w]
     (#'clojure.core/print-meta rx w)
     (.write w "#")
     (.write w (.getName re_db.reactive.Reaction))
     (#'clojure.core/print-map (merge {:detached (detached? rx)
                                       :stale (stale? rx)
                                       :peek (peek rx)}
                                      (select-keys (meta rx) [:display-name])) #'clojure.core/pr-on w)))


;; todo
;; detached -> detached ?
;; frp vs streams
;; frtime "father time" racket library - done in scheme, idioms more similar to clj
