(ns re-db.reactive
  (:refer-clojure :exclude [-peek peek atom])
  (:require [applied-science.js-interop :as j]
            [re-db.util :as util]
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
  (set-watches! [this new-watches])
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
  (deftype RAtom [^:volatile-mutable state ^:volatile-mutable watches ^:volatile-mutable meta]
    IWatchable*
    (get-watches [this] watches)
    (set-watches! [this! new-watches] (set! watches new-watches))
    (notify-watches! [this old-val new-val] (notify-watches this old-val new-val watches))
    IMeta
    (-meta [this] meta)
    IUpdateMeta
    (update-meta! [this f] (set! meta (f meta)) this)
    (update-meta! [this f a] (set! meta (f meta a)) this)
    (update-meta! [this f a b] (set! meta (f meta a b)) this)
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
      this)))

(defn atom
  ([initial-value]
   (->RAtom initial-value {} {}))
  ([initial-value meta]
   (->RAtom initial-value {} meta)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reaction - reactive source derived from a `compute` function which "captures" dependencies
;;   dereferenced during evaluation. (modeled on Reagent reactions)

(util/support-clj-protocols
  (deftype Reaction
    [^:volatile-mutable f ratom ^:volatile-mutable derefs ^:volatile-mutable hooks ^:volatile-mutable dispose-fns !dirty? !eager?]
    IWatchable*
    (get-watches [this] (get-watches ratom))
    (set-watches! [this new-watches] (set-watches! ratom new-watches))
    (notify-watches! [this old-val new-val] (notify-watches this old-val new-val (get-watches ratom)))
    IMeta
    (-meta [this] (meta ratom))
    IUpdateMeta
    (update-meta! [this f] (update-meta! ratom f))
    (update-meta! [this f a] (update-meta! ratom f a))
    (update-meta! [this f a b] (update-meta! ratom f a b))
    IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))
    IDispose
    (get-dispose-fns [this] dispose-fns)
    (set-dispose-fns! [this new-dispose-fns] (set! dispose-fns new-dispose-fns))
    (on-dispose [this]
      (dispose-derefs! this)
      (dispose-hooks! this)
      (vreset! !dirty? true)
     ;; clean up the atom's watches
      (when-let [ks (seq (keys (get-watches ratom)))]
        (doseq [k ks]
          (when (identical? (type k) Reaction)
            (prn "Disposing a reaction that is a dependency of another reaction"
                 :this (peek ratom)
                 :other (peek k)))
          (remove-watch ratom k))))

    IDeref
    (-deref [this]

      (when @!dirty?
        (when-not (owner)
          (throw (ex-info "an inactive reaction cannot be dereferenced without a reactive context. wrap in r/session for immediate disposal." {:reaction this})))
        (invalidate! this))

      (when-not (identical? *owner* this)
        (collect-deref! this))

      (peek ratom))

    IPeek
    (-peek [this] (-peek ratom))
    IReset
    (-reset! [this new-val] (reset! ratom new-val))
    ISwap
    (-swap! [this f] (reset! ratom (f (peek ratom))))
    (-swap! [this f x] (reset! ratom (f (peek ratom) x)))
    (-swap! [this f x y] (reset! ratom (f (peek ratom) x y)))
    (-swap! [this f x y args] (reset! ratom (apply f (peek ratom) x y args)))
    IWatchable
    (-add-watch [this key f]

     ;; if the reaction is not yet initialized, do it here (before adding the watch,
     ;; so that the watch-fn is not called here)
      (when @!dirty? (invalidate! this))

      (add-watch ratom key f)
      this)
    (-remove-watch [this key]
      (remove-watch ratom key)
      (when (and (empty? (get-watches ratom)) (not @!eager?))
        (dispose! this))
      this)
    ICaptureDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ICompute
    (compute [this] (f))
    (invalidate! [this]
      (vreset! !dirty? false)
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
  (when (seq watches)
    (doseq [[consumer f] watches]
      (add-watch to consumer f))
    (let [new-val @to]
      (when (not= old-val new-val)
        (notify-watches! to old-val new-val))))
  to)

(defn conj-some
  "Conj non-nil values to coll"
  ([coll x]
   (cond-> coll (some? x) (conj x)))
  ([coll x & args]
   (reduce conj-some (conj-some coll x) args)))

(defn make-reaction [compute-fn & {:as opts
                                   :keys [init meta on-dispose dirty? eager?]
                                   :or {dirty? true
                                        eager? false}}]
  (cond-> (->Reaction compute-fn
                      (atom init meta)
                      empty-derefs
                      empty-hooks
                      (if on-dispose [on-dispose] empty-dispose-fns)
                      (volatile! dirty?)
                      (volatile! eager?))
          eager? invalidate!))

(defn eager!
  "Computes a reaction immediately and remains active until explicitly disposed."
  [#?(:clj ^re_db.reactive.Reaction rx :cljs ^Reaction rx)]
  (let [!e (.-!eager? rx)]
    (when-not @!e
      (vreset! !e true)
      (when @(.-!dirty? rx)
        (invalidate! rx))))
  rx)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (make-reaction (fn []) :dirty? false))

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
(defmacro reaction! [& body] (concat (macros/reaction* &form &env body) [:eager? true]))

#?(:clj
   (defmethod print-method re_db.reactive.Reaction
     [^re_db.reactive.Reaction m w]
     (.write w (str "Reaction[" (:display-name (meta m)) "]"))
     #_(#'clojure.core/print-object (meta m) w)
     ))