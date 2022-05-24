(ns re-db.reactive
  (:refer-clojure :exclude [-peek peek atom])
  (:require [re-db.util :as util]
            [re-db.macros :as r]
            #?(:cljs [applied-science.js-interop :as j]))
  #?(:cljs (:require-macros re-db.reactive)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invalidation - for recomputing reactive sources

(defprotocol ICompute
  (-set-function! [this new-f])
  (compute [this])
  (invalidate! [this]))

(defn reset-function! [reaction new-f]
  (-set-function! reaction new-f)
  (invalidate! reaction))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Disposal - for side effecting cleanup

(defprotocol IDispose
  (get-dispose-fns [this])
  (set-dispose-fns! [this fns]))

(defn dispose! [this]
  (doseq [f (get-dispose-fns this)] (f this))
  (set-dispose-fns! this [])
  nil)

(defn add-on-dispose!
  ([this f]
   (set-dispose-fns! this (conj (get-dispose-fns this) f)))
  ([this f & fns] (set-dispose-fns! this (apply conj (get-dispose-fns this) f fns))))

(def empty-dispose-fns [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ownership - for multiple purposes: dependency tracking, disposal, hooks,

(def ^:dynamic *owner* nil)

(defn owner []
  (or *owner*
      #?(:cljs
         (when util/reagent-compat
           (util/js-resolve-sym reagent.ratom/*ratom-context*)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deref Capture - for dependency on reactive sources

(defprotocol ICaptureDerefs
  (get-derefs [this])
  (set-derefs! [this new-derefs]))

(def ^:dynamic *captured-derefs* nil)

(defn collect-deref! [producer]
  #?(:cljs (util/reagent-notify-deref-watcher! producer))
  (some-> *captured-derefs* (vswap! conj producer)))

(defn handle-new-derefs! [consumer new-derefs]
  (let [derefs (get-derefs consumer)
        [added removed] (util/set-diff (util/guard derefs seq)
                                       (util/guard new-derefs seq))]
    (doseq [producer added] (add-watch producer consumer (fn [key _ _ _] (invalidate! key))))
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
  (if (satisfies? IPeek this) (-peek this) (r/without-deref-capture @this)))

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

(defn dispose-hooks! [this]
  (doseq [hook (get-hooks this)
          :let [dispose (:dispose @hook)]]
    (when dispose (dispose))))

(def empty-hooks [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reactive Atom - simplest building-block

(defn notify-watches [ref old-val new-val watches]
  (doseq [[k f] watches] (f k ref old-val new-val)))

(util/support-clj-protocols
   (deftype RAtom [^:volatile-mutable state ^:volatile-mutable watches ^:volatile-mutable dispose-fns meta]
     ICompute
     (invalidate! [this]
       (notify-watches this -1 state watches))
     IMeta
     (-meta [this] meta)
     IDispose
     (get-dispose-fns [this] dispose-fns)
     (set-dispose-fns! [this new-dispose-fns] (set! dispose-fns new-dispose-fns))
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
         (notify-watches this old-val new-val watches)
         new-val))
     ISwap
     (-swap! [this f] (reset! this (f state)))
     (-swap! [this f x] (reset! this (f state x)))
     (-swap! [this f x y] (reset! this (f state x y)))
     (-swap! [this f x y args] (reset! this (apply f state x y args)))
     IWatchable
     (-add-watch [this key f]
       (r/set-swap! watches assoc key f)
       this)
     (-remove-watch [this key]
       (r/set-swap! watches dissoc key)
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
    IMeta
    (-meta [this] (meta ratom))
    IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))
    IDeref
    (-deref [this]
      (when dirty? (invalidate! this) (set! dirty? false))
      @ratom)
    IPeek
    (-peek [this] (-peek ratom))
    IReset
    (-reset! [this new-val] (reset! ratom new-val))
    IDispose
    (get-dispose-fns [this] (get-dispose-fns ratom))
    (set-dispose-fns! [this dispose-fns] (set-dispose-fns! ratom dispose-fns))
    IWatchable
    (-add-watch [this key f] (add-watch ratom key f) this)
    (-remove-watch [this key] (remove-watch ratom key) this)
    ICaptureDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ICompute
    (-set-function! [this new-f] (set! f new-f))
    (compute [this] (f))
    (invalidate! [this]
      (let [new-val (r/with-owner this
                      (r/with-hook-support!
                       (r/with-deref-capture!
                        (f))))]
        (when (not= (peek ratom) new-val)
          (reset! ratom new-val))
        this))))

(defn conj-some
  "Conj non-nil values to coll"
  ([coll x]
   (cond-> coll (some? x) (conj x)))
  ([coll x & args]
   (reduce conj-some (conj-some coll x) args)))

(defn make-reaction [compute-fn & {:keys [on-dispose init dirty? meta]
                                   :or {dirty? true}}]
  (let [state (atom init meta)
        rxn (->Reaction compute-fn
                        state
                        empty-derefs
                        empty-hooks
                        dirty?)]
    (add-on-dispose! state (fn [_]
                             (dispose-derefs! rxn)
                             (dispose-hooks! rxn)
                             (when on-dispose (on-dispose rxn))))
    rxn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sessions - for repl/dev work, use reactions from within a session and
;; clean up afterwards

(defn make-session []
  (let [ratom (atom nil)
        !derefs (volatile! [])
        session (util/support-clj-protocols
                  (reify
                    IDispose
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
(defmacro with-owner [& args] `(r/with-owner ~@args))
(defmacro with-deref-capture! [& args] `(r/with-deref-capture! ~@args))
(defmacro without-deref-capture [& args] `(r/without-deref-capture ~@args))
(defmacro with-hook-support! [& args] `(r/with-hook-support! ~@args))
(defmacro reaction [& args] `(r/reaction ~@args))
(defmacro reaction! [& args] `(r/reaction! ~@args))
(defmacro with-session [& args] `(r/with-session ~@args))
(defmacro session [& body] `(r/session ~@body))