(ns re-db.reactive
  (:refer-clojure :exclude [atom peek])
  (:require [re-db.macros :as macros]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.reactive)))

(defprotocol ICompute
  "Protocol for signals that have a compute function"
  (compute! [this] "(re)compute the value of this, returns true if value changed")
  (set-stale! [this] "mark instance as stale")
  (stale? [this] "the instance must be (re)computed before a valid value can be read")
  (computes? [this] "marker method - returns false for default impl"))

(defprotocol ICountReferences
  "Protocol for types that track watches and dispose when unwatched.

   An instance can be detached, which means it remains active regardless of watched status
   and will never be automatically disposed."
  (get-watches [this])
  (set-watches! [this new-watches])
  (add-on-dispose! [this f])
  (dispose! [this])
  (detach! [this] "Detach from graph - computes immediately and remains active until explicitly disposed")
  (detached? [this]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deref Tracking - for implicitly recording dependencies via `deref`

(defprotocol ITrackDerefs
  "for dependency on reactive sources"
  (get-derefs [this])
  (set-derefs! [this new-derefs]))

(def ^:dynamic *captured-derefs* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dynamic metadata

(defprotocol IMutableMeta
  (update-meta! [this f] [this f x] [this f x y] [this f x y args]))

(extend-type #?(:clj Object :cljs object)
  IMutableMeta
  (update-meta!
    ([this f] this)
    ([this f x] this)
    ([this f x y] this)
    ([this f x y args] this))

  ICountReferences
  (get-watches [this] this)
  (set-watches! [this new-watches] this)
  (add-on-dispose! [this f] this)
  (dispose! [this] this)
  (detach! [this] this)
  (detached? [this] true)

  ICompute
  (compute! [this] false)
  (stale? [this] false)
  (set-stale! [this] this)
  (computes? [this] false))

(def empty-watches {})
(def empty-dispose-fns [])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IPeek
  "for reading reactive values without triggering a dependency relationship.
   Note: this does not trigger computation of disposed/stale sources."
  (peek [this]))

(extend-protocol IPeek
  #?(:clj Object :cljs object)
  (peek [this] (macros/without-deref-capture @this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notify
;;
;; When a ref changes, notify dependents (watches) in topological order,
;; once per epocph.

(defn notify-watches
  [ref old-val new-val]
  #_(doseq [[k f] (get-watches ref)] (f k ref old-val new-val))

  (let [watches (get-watches ref)]
    ;; mark immediate depts as stale before evaluating any of them
    ;; (causes transitive depts to evaluate in order)
    (doseq [ref (keys watches)] (set-stale! ref))

    (doseq [[k f] watches]
      (if (computes? k)
        (when (stale? k) (compute! k))
        (f k ref old-val new-val)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *owner*
  "Owner of the current reactive computation"
  nil)

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
  "Like `def` but if name already exists, migrates old reaction to new reaction using become"
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
;; Reactive Atom - for holding values, with reference tracking and disposal

(declare collect-deref!)

(util/support-clj-protocols
  (deftype RAtom [^:volatile-mutable state
                  ^:volatile-mutable watches
                  ^:volatile-mutable metadata
                  ^:volatile-mutable dispose-fns
                  ^:volatile-mutable detached]
    ICountReferences
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
      (macros/set-swap! dispose-fns empty))
    IMeta
    (-meta [this] metadata)
    IMutableMeta
    (update-meta! [this f] (set! metadata (f metadata)))
    (update-meta! [this f x] (set! metadata (f metadata x)))
    (update-meta! [this f x y] (set! metadata (f metadata x y)))
    (update-meta! [this f x y args] (set! metadata (apply f metadata x y args)))
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
   (->RAtom initial-value {} {} [] false))
  ([initial-value & {:keys [meta detached on-dispose]
                     :or {meta {}
                          detached false}}]
   (->RAtom initial-value
            empty-watches
            meta
            (cond-> empty-dispose-fns
                    on-dispose
                    (conj on-dispose))
            detached)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



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

(def empty-derefs #{})

(defn captured-patterns []
  (->> @*captured-derefs*
       (into #{} (map (comp :pattern meta)))))

(defprotocol IHook
  "for composable side-effects within reactions (modeled on React hooks).
   See re-db.hooks for the api."
  (get-hooks [this])
  (set-hooks! [this hooks]))

(defn get-hook [this i] (nth (get-hooks this) i nil))
(defn set-hook! [this i hook]
  (set-hooks! this (assoc (get-hooks this) i hook))
  hook)

(def ^:dynamic *hook-i*)

(defn get-next-hook! [expected-type]
  {:post [(volatile? %)]}
  (let [owner *owner*
        i (vswap! *hook-i* inc)
        !hook (get-hook owner i)]
    (when !hook
      (assert (= (:type (meta @!hook)) expected-type)
              (str "expected hook of type " expected-type ", but found " (:type @!hook) ". "
                   "A reaction must always call the same hooks in the same order on every evaluation.")))
    (or !hook
        (set-hook! owner i (volatile! (with-meta {} {:type expected-type}))))))

(defn fresh? [hook] (empty? hook))

(def empty-hooks [])

(defn dispose-hooks! [this]
  (let [hooks (get-hooks this)]
    (set-hooks! this empty-hooks)
    (doseq [hook hooks
            :let [dispose (:dispose @hook)]]
      (when dispose (dispose)))))


(util/support-clj-protocols
  (deftype Reaction
    [^:volatile-mutable f
     ^:volatile-mutable state
     ^:volatile-mutable derefs
     ^:volatile-mutable hooks
     ^:volatile-mutable dispose-fns
     ^:volatile-mutable watches
     ^:volatile-mutable metadata
     ^:volatile-mutable stale
     ^:volatile-mutable detached]
    ICountReferences
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
      (dispose-hooks! this)
      (set! stale true)
      (doseq [f dispose-fns] (f this))
      (macros/set-swap! dispose-fns empty))
    IMeta
    (-meta [this] metadata)
    IMutableMeta
    (update-meta! [this f] (set! metadata (f metadata)))
    (update-meta! [this f x] (set! metadata (f metadata x)))
    (update-meta! [this f x y] (set! metadata (f metadata x y)))
    (update-meta! [this f x y args] (set! metadata (apply f metadata x y args)))
    IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))

    IDeref
    (-deref [this]

      (when-not (identical? *owner* this)
        (collect-deref! this))

      (when stale (compute! this))

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
    (-add-watch [this key f]

     ;; if the reaction is not yet initialized, do it here (before adding the watch,
     ;; so that the watch-fn is not called here)
      (when stale (compute! this))

      (macros/set-swap! watches assoc key f)
      this)
    (-remove-watch [this key]
      (macros/set-swap! watches dissoc key)
      (when (and (empty? watches) (not detached))
        (dispose! this))
      this)
    ITrackDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ICompute
    (stale? [this] stale)
    (set-stale! [this] (set! stale true))
    (compute! [this]
      (set! stale false)
      (let [new-val (macros/with-owner this
                      (macros/with-hook-support!
                       (macros/with-deref-capture! this
                         (f))))
            changed? (not= state new-val)]
        (when changed?
          (reset! this new-val))
        changed?))
    (computes? [this] true)))

(defn make-reaction [compute-fn & {:as opts
                                   :keys [init
                                          meta
                                          on-dispose
                                          stale
                                          detached]
                                   :or {stale true
                                        detached false}}]
  (cond-> (->Reaction compute-fn
                      init
                      empty-derefs
                      empty-hooks
                      (if on-dispose [on-dispose] empty-dispose-fns)
                      empty-watches
                      meta
                      stale
                      detached)
          detached (doto compute!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (make-reaction (fn []) :stale false))

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
(defmacro reaction [& body] (macros/reaction* &form &env body))
(defmacro reaction! [& body] (concat (macros/reaction* &form &env body) [:detached true]))

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