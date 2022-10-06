(ns re-db.macros
  (:require [clojure.walk :as walk])
  #_(:require [re-db.reactive :as-alias r]) ;; TODO when cursive fixes bug https://github.com/cursive-ide/cursive/issues/2690
  #?(:cljs (:require-macros re-db.macros)))

;; Macro implementations are kept separate from macros to facilitate usage from sci

(defmacro set-swap! [sym f & args]
  `(set! ~sym (~f ~sym ~@args)))

(defmacro with-deref-capture! [owner & body]
  `(binding [~'re-db.reactive/*captured-derefs* (volatile! ~'re-db.reactive/empty-derefs)]
     (let [val# (do ~@body)
           new-derefs# @~'re-db.reactive/*captured-derefs*]
       (~'re-db.reactive/handle-new-derefs! ~owner new-derefs#)
       val#)))

(defn -without-deref-capture [body]
  `(binding [~'re-db.reactive/*captured-derefs* nil] ~@body))

(defmacro without-deref-capture [& body] (-without-deref-capture body))


(defn -with-hook-support! [body]
  `(binding [~'re-db.reactive/*hook-i* (volatile! -1)]
     ~@body))

(defmacro with-hook-support! [& body] (-with-hook-support! body))

(defn -with-owner [owner body]
  `(let [owner# ~owner]
     (binding [~'re-db.reactive/*owner* owner#]
       ~@body)))

(defmacro with-owner [owner & body] (-with-owner owner body))

(defn -reaction
  "Returns a derefable reactive source based on body. Re-evaluates body when any dependencies (captured derefs) change. Lazy."
  [body]
  `(~'re-db.reactive/make-reaction (fn [] ~@body)))

(defmacro reaction [& body] (-reaction body))

(defn reaction!:impl [body]
  `(doto (~'re-db.reactive/make-reaction (fn [] ~@body)) deref))

(defmacro reaction!
  "Eager version of reaction"
  [& body] (-reaction body))

(defmacro session
  "Evaluate body in a reaction which is immediately disposed"
  [& body]
  `(let [rx# (reaction ~@body)
         v# @rx#]
     (re-db.reactive/dispose! rx#)
     v#))

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