(ns re-db.macros
  (:require [clojure.walk :as walk])
  #_(:require [re-db.reactive :as-alias r]) ;; TODO when cursive fixes bug https://github.com/cursive-ide/cursive/issues/2690
  #?(:cljs (:require-macros re-db.macros)))

(defmacro set-swap! [sym f & args]
  `(set! ~sym (~f ~sym ~@args)))

(defmacro with-owner [owner & body]
  `(binding [re-db.reactive/*owner* ~owner] ~@body))

(defmacro with-deref-capture! [& body]
  `(binding [re-db.reactive/*captured-derefs* (volatile! re-db.reactive/empty-derefs)]
     (let [val# (do ~@body)
           new-derefs# @re-db.reactive/*captured-derefs*]
       (re-db.reactive/handle-new-derefs! re-db.reactive/*owner* new-derefs#)
       val#)))

(defmacro without-deref-capture [& body]
  `(binding [re-db.reactive/*captured-derefs* nil] ~@body))

(defmacro with-hook-support! [& body]
  `(binding [re-db.reactive/*hook-i* (volatile! -1)]
     ~@body))

(defmacro reaction
  "Returns a derefable reactive source based on body. Re-evaluates body when any dependencies (captured derefs) change. Lazy."
  [& body]
  `(re-db.reactive/make-reaction (fn [] ~@body)))

(defmacro reaction!
  "Eager version of reaction"
  [& body]
  `(doto (re-db.reactive/make-reaction (fn [] ~@body)) re-db.reactive/invalidate!))

;; for dev - only adds derefs
(defmacro with-session
  "Evaluates body, accumulating dependencies in session (for later disposal)"
  [session & body]
  `(let [session# ~session]
     (with-owner session#
       (binding [re-db.reactive/*captured-derefs* (volatile! re-db.reactive/empty-derefs)]
         (let [val# (do ~@body)
               new-derefs# @re-db.reactive/*captured-derefs*]
           (doseq [producer# new-derefs#] (add-watch producer# session# (fn [& args#])))
           (re-db.reactive/set-derefs! session# (into (re-db.reactive/get-derefs session#) new-derefs#))
           val#)))))

(defmacro session
  "[] - returns a session.
   [& body] - Evaluates body in a reactive session which is immediately disposed"
  ([] `(~'re-db.reactive/make-session))
  ([& body]
   `(let [s# (re-db.reactive/make-session)
          v# (with-session s# ~@body)]
      (re-db.reactive/dispose! s#)
      v#)))

(defn dequote [x]
  (cond-> x
          (and (seq? x) (= 'quote (first x)))
          second))

(defmacro defpartial
  "Defines a partially-applied function with static arities
   and arbitrary

  Syntax is like `defn` with an options map containing :f,
  an expression containing `_` where args should be spliced in

  eg
  (defpartial add-to-1 {:f `(+ 1 _)} ([x]) ([x y]))"
  [name {:keys [f]} & arglists]
  (let [f (dequote f)]
    `(defn ~name
       ~@(for [argv arglists
               :let [argv (if (list? argv) (first argv) argv)]]
           (list argv (walk/postwalk (fn [x]
                                       (if (list? x)
                                         (mapcat (fn [x] (if (= x '_) argv [x])) x)
                                         x)) f))))))

(comment
 '(defpartial f {:f '(a/f _ *db*)}
    ([a])
    ([a b]))
 '(fn f
    ([a] (a/f a *db*))
    ([a b] (a/f a b *db*)))

 (macroexpand-1
  '(re-db.macros/defpartial
     get {:f '(read/get *db* _)}
     ([id attr])
     ([id attr not-found]))))