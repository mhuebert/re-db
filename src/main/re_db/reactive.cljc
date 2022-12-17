(ns re-db.reactive
  (:refer-clojure :exclude [-peek peek atom])
  (:require [re-db.macros :as macros]
            [re-db.util :as util])
  #?(:cljs (:require-macros re-db.reactive)))

(defprotocol ICountReferences
  "Protocol for types that track watches and dispose when unwatched.

   An instance can be independent, which means it remains active regardless of watched status
   and will never be automatically disposed."
  (get-watches [this])
  (set-watches! [this new-watches])
  (add-on-dispose! [this f])
  (dispose! [this])
  (independent! [this] "Compute immediately and remain active even when unwatched")
  (independent? [this]))

(extend-protocol ICountReferences
  #?(:clj Object :cljs object)
  (get-watches [this] this)
  (set-watches! [this new-watches] this)
  (add-on-dispose! [this f] this)
  (dispose! [this] this)
  (independent! [this] this)
  (independent? [this] true))

(def empty-watches {})
(def empty-dispose-fns [])

(defn notify-watches [ref old-val new-val]
  (doseq [[k f] (get-watches ref)] (f k ref old-val new-val)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *owner*
  "Owner of the current reactive computation"
  nil)

#?(:cljs (defonce get-reagent-context (constantly nil)))

(defn owner []
  #?(:clj  *owner*
     :cljs (or *owner* (get-reagent-context))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:dynamic *captured-derefs*)

(defprotocol IPeek
  "for reading reactive values without triggering a dependency relationship.
   Note: this does not trigger computation of disposed/inactive sources."
  (-peek [this]))

(defn peek [this]
  (if (satisfies? IPeek this) (-peek this) (macros/without-deref-capture @this)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reactive Atom - for holding values, with reference tracking and disposal

(declare collect-deref!)

(util/support-clj-protocols
  (deftype RAtom [^:volatile-mutable state
                  ^:volatile-mutable watches
                  ^:volatile-mutable meta
                  ^:volatile-mutable dispose-fns
                  ^:volatile-mutable independent]
    ICountReferences
    (get-watches [this] watches)
    (set-watches! [this! new-watches] (set! watches new-watches))
    (independent? [this] independent)
    (independent! [this]
      (when-not independent
        (set! independent true)))
    (add-on-dispose! [this f]
      (macros/set-swap! dispose-fns conj f))
    (dispose! [this]
      (doseq [f dispose-fns] (f this))
      (macros/set-swap! dispose-fns empty))
    IMeta
    (-meta [this] meta)
    IPeek
    (-peek [this] state)
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
      (when (and (empty? watches) (not independent))
        (dispose! this))
      this)))

(defn atom
  ([initial-value]
   (->RAtom initial-value {} {} [] false))
  ([initial-value & {:keys [meta independent on-dispose]
                     :or {meta {}
                          independent false}}]
   (->RAtom initial-value
            empty-watches
            meta
            (cond-> empty-dispose-fns
                    on-dispose
                    (conj on-dispose))
            independent)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ICompute
  "Protocol for reactive sources that compute their own value"
  (compute! [this] "(re)compute the value of this")
  (inactive? [this] "the instance does not have a valid value and must be (re)computed"))

(extend-protocol ICompute
  #?(:clj Object :cljs object)
  (compute! [this] this)
  (inactive? [this] false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deref Tracking - for implicitly recording dependencies via `deref`

(defprotocol ITrackDerefs
  "for dependency on reactive sources"
  (get-derefs [this])
  (set-derefs! [this new-derefs]))

(def ^:dynamic *captured-derefs* nil)

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
     ^:volatile-mutable inactive
     ^:volatile-mutable independent]
    ICountReferences
    (get-watches [this] watches)
    (set-watches! [this new-watches] (set! watches new-watches))
    (independent? [this] independent)
    (independent! [this]
      (when-not independent
        (set! independent true)
        (compute! this))
      this)
    (add-on-dispose! [this f]
      (macros/set-swap! dispose-fns conj f))
    (dispose! [this]
      (dispose-derefs! this)
      (dispose-hooks! this)
      (set! inactive true)
      (doseq [f dispose-fns] (f this))
      (macros/set-swap! dispose-fns empty))
    IMeta
    (-meta [this] metadata)
    IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))

    IDeref
    (-deref [this]

      (when-not (identical? *owner* this)
        (collect-deref! this))

      (when inactive (compute! this))

      state)

    IPeek
    (-peek [this] state)
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
      (when inactive (compute! this))

      (macros/set-swap! watches assoc key f)
      this)
    (-remove-watch [this key]
      (macros/set-swap! watches dissoc key)
      (when (and (empty? watches) (not independent))
        (dispose! this))
      this)
    ITrackDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ICompute
    (inactive? [this] inactive)
    (compute! [this]
      (set! inactive false)
      (let [new-val (macros/with-owner this
                      (macros/with-hook-support!
                       (macros/with-deref-capture! this
                         (f))))]
        (when (not= state new-val)
          (reset! this new-val))
        this))))

(defn make-reaction [compute-fn & {:as opts
                                   :keys [init
                                          meta
                                          on-dispose
                                          inactive
                                          independent]
                                   :or {inactive true
                                        independent false}}]
  (cond-> (->Reaction compute-fn
                      init
                      empty-derefs
                      empty-hooks
                      (if on-dispose [on-dispose] empty-dispose-fns)
                      empty-watches
                      meta
                      inactive
                      independent)
          independent compute!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (make-reaction (fn []) :inactive false))

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
(defmacro reaction! [& body] (concat (macros/reaction* &form &env body) [:independent true]))

#?(:clj
   (defmethod print-method re_db.reactive.Reaction
     [^re_db.reactive.Reaction rx w]
     (#'clojure.core/print-meta rx w)
     (.write w "#")
     (.write w (.getName re_db.reactive.Reaction))
     (#'clojure.core/print-map (merge {:independent (independent? rx)
                                       :inactive (inactive? rx)
                                       :peek (peek rx)}
                                      (select-keys (meta rx) [:display-name])) #'clojure.core/pr-on w)))