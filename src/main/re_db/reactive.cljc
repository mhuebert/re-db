(ns re-db.reactive
  (:refer-clojure :exclude [-peek peek atom])
  (:require [re-db.util :as util]
            [re-db.macros :as macros])
  #?(:cljs (:require-macros re-db.reactive)))

;; TODO - write tests for re-registering reactions

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invalidation - for recomputing reactive sources

(defprotocol ICompute
  (compute [this])
  (invalidate! [this]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Disposal - for side effecting cleanup

(defprotocol IDispose
  (get-dispose-fns [this])
  (set-dispose-fns! [this fns])
  (on-dispose [this]))

(def empty-dispose-fns [])

(defn dispose! [this]
  (on-dispose this)
  (let [dispose-fns (get-dispose-fns this)]
    (set-dispose-fns! this [])
    (doseq [f dispose-fns] (f this)))
  nil)

(defn add-on-dispose!
  ([this f]
   (set-dispose-fns! this (conj (get-dispose-fns this) f)))
  ([this f & fns] (set-dispose-fns! this (apply conj (get-dispose-fns this) f fns))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ownership - for multiple purposes: dependency tracking, disposal, hooks,

(def ^:dynamic *owner* nil)

#?(:cljs (defonce get-reagent-context (constantly nil)))

(defn owner []
  (or *owner* #?(:cljs (get-reagent-context))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Access to 'watches' field for copying watches

(defprotocol IWatchable*
  (get-watches [this])
  (notify-watches! [this old-val new-val]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deref Capture - for dependency on reactive sources

(defprotocol ICaptureDerefs
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
    (doseq [producer added] (add-watch producer consumer (fn [_ _ _ _] (invalidate! consumer))))
    (doseq [producer removed] (remove-watch producer consumer))
    (set-derefs! consumer new-derefs)
    consumer))

(defn dispose-derefs! [consumer] (handle-new-derefs! consumer nil))

(def empty-derefs #{})

(defn captured-patterns []
  (->> @*captured-derefs*
       (into #{} (map (comp :pattern meta)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Peek - for reading a reactive value without triggering a dependency

(defprotocol IPeek
  (-peek [this]))

(defn peek [this]
  (if (satisfies? IPeek this) (-peek this) (macros/without-deref-capture @this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Hooks - for composable side-effects within reactions (modeled on React hooks).
;; See `re-db.hooks` for API.

(defprotocol IHook
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reactive Atom - simplest building-block

(defn notify-watches [ref old-val new-val watches]
  (doseq [[k f] watches] (f k ref old-val new-val)))

(defprotocol IUpdateMeta
  (update-meta! [x f] [x f a] [x f a b]))

(util/support-clj-protocols
  (deftype RAtom [^:volatile-mutable state ^:volatile-mutable watches ^:volatile-mutable dispose-fns ^:volatile-mutable meta]
    IWatchable*
    (get-watches [this] watches)
    (notify-watches! [this old-val new-val] (notify-watches this old-val new-val watches))
    IMeta
    (-meta [this] meta)
    IUpdateMeta
    (update-meta! [this f] (set! meta (f meta)) this)
    (update-meta! [this f a] (set! meta (f meta a)) this)
    (update-meta! [this f a b] (set! meta (f meta a b)) this)
    IDispose
    (get-dispose-fns [this] dispose-fns)
    (set-dispose-fns! [this new-dispose-fns] (set! dispose-fns new-dispose-fns))
    (on-dispose [this] (set! watches {}))
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
        (notify-watches! this old-val new-val)
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
      (when (empty? watches) (dispose! this))
      this)))

(defn atom
  ([initial-value]
   (->RAtom initial-value {} [] nil))
  ([initial-value meta]
   (->RAtom initial-value {} [] meta)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reaction - reactive source derived from a `compute` function which "captures" dependencies
;;   dereferenced during evaluation. (modeled on Reagent reactions)

(util/support-clj-protocols
  (deftype Reaction
    [^:volatile-mutable f ratom ^:volatile-mutable derefs ^:volatile-mutable hooks ^:volatile-mutable dirty?]
    IWatchable*
    (get-watches [this] (get-watches ratom))
    (notify-watches! [this old-val new-val] (notify-watches! ratom old-val new-val))
    IMeta
    (-meta [this] (meta ratom))
    IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))
    IDeref
    (-deref [this]
      (when dirty? (invalidate! this))
      (let [v @ratom]
        ;; when not in a reactive context, immediately dispose
        (when (and (not (owner))
                   (empty? (get-watches ratom)))
          (dispose! this))
        v))
    IPeek
    (-peek [this] (-peek ratom))
    IReset
    (-reset! [this new-val] (reset! ratom new-val))
    IDispose
    (get-dispose-fns [this] (get-dispose-fns ratom))
    (set-dispose-fns! [this dispose-fns] (set-dispose-fns! ratom dispose-fns))
    (on-dispose [this]
      (set! dirty? true)
      (dispose-derefs! this)
      (dispose-hooks! this))
    IWatchable
    (-add-watch [this key f]
      (add-watch ratom key f)
      (when dirty? (invalidate! this))
      this)
    (-remove-watch [this key] (remove-watch ratom key) this)
    ICaptureDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ICompute
    (compute [this] (f))
    (invalidate! [this]
      (set! dirty? false)
      (let [new-val (macros/with-owner this
                      (macros/with-hook-support!
                       (macros/with-deref-capture! this
                         (f))))]
        (when (not= (peek ratom) new-val)
          (reset! ratom new-val))
        this))))

(defn migrate-watches!
  "Utility for transparently swapping out a watchable while keeping watches informed"
  [watches old-val to]
  (let [new-val @to]
    (doseq [[consumer f] watches]
      (add-watch to consumer f))
    (when (not= old-val new-val)
      (notify-watches! to old-val new-val)))
  to)

(defn conj-some
  "Conj non-nil values to coll"
  ([coll x]
   (cond-> coll (some? x) (conj x)))
  ([coll x & args]
   (reduce conj-some (conj-some coll x) args)))

(defn make-reaction [compute-fn & {:as opts
                                   :keys [init dirty? meta]
                                   :or {dirty? true}}]
  (assert (not (:on-dispose opts)) "on-dispose is deprecated, use use-effect hook instead.")
  (let [a (atom init meta)
        rx (->Reaction compute-fn
                       a
                       empty-derefs
                       empty-hooks
                       dirty?)]
    (add-on-dispose! a (fn this [ratom]
                         (on-dispose rx)
                         (add-on-dispose! ratom this)))
    rx))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (let [ratom (atom nil)
        !derefs (volatile! [])
        session (util/support-clj-protocols
                  (reify
                    IDispose
                    (on-dispose [this])
                    (get-dispose-fns [this] (get-dispose-fns ratom))
                    (set-dispose-fns! [this new-dispose-fns] (set-dispose-fns! ratom new-dispose-fns))
                    IWatchable
                    (-add-watch [this key f] (add-watch ratom key f)
                      this)
                    (-remove-watch [this key] (remove-watch ratom key)
                      this)
                    ICaptureDerefs
                    (get-derefs [this] @!derefs)
                    (set-derefs! [this new-derefs] (vreset! !derefs new-derefs))))]
    (add-on-dispose! ratom
                     (fn [_]
                       (doseq [producer @!derefs] (remove-watch producer session) dispose!)))
    session))

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
(defmacro reaction [& body] `(macros/reaction ~@body))
(defmacro reaction! [& body] `(macros/reaction! ~@body))