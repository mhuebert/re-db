(ns re-db.macros
  (:require [clojure.walk :as walk]
            [re-db.reactive :as-alias r])
  #?(:cljs (:require-macros re-db.macros)))

;; Macro implementations are kept separate from macros to facilitate usage from sci

(defmacro set-swap! [sym f & args]
  `(set! ~sym (~f ~sym ~@args)))

(defmacro with-deref-capture! [owner & body]
  `(binding [r/*captured-derefs* (volatile! r/init-derefs)]
     (let [val# (do ~@body)
           new-derefs# @r/*captured-derefs*]
       (r/handle-new-derefs! ~owner new-derefs#)
       val#)))

(defn -without-deref-capture [body]
  `(binding [r/*captured-derefs* nil] ~@body))

(defmacro without-deref-capture [& body] (-without-deref-capture body))

(defn -with-hook-support! [body]
  `(binding [~'re-db.impl.hooks/*hook-i* (volatile! -1)]
     ~@body))

(defmacro with-hook-support! [& body] (-with-hook-support! body))

(defn -with-owner [owner body]
  `(let [owner# ~owner]
     (binding [r/*owner* owner#]
       ~@body)))

(defmacro with-owner [owner & body] (-with-owner owner body))

(defn dev-meta [form]
  {:display-name (str *ns* "@"
                      (:line (meta form))
                      ":"
                      (:column (meta form)))})

(defmacro reaction
  "Returns a lazy derefable reactive source computed from `body`. Captures dependencies and recomputes
   when they change. Disposes self when last watch is removed."
  ([expr] `(reaction {} ~expr))
  ([options & body]
   (let [[options body] (if (map? options) [options body] [{} (cons options body)])
         options (update options :meta merge (dev-meta &form))]
     `(r/make-reaction ~options (fn [] ~@body)))))

(defmacro reaction!
  "Returns an detached reaction: computes immediately, remains active until explicitly disposed."
  [& body]
  `(r/detach! (reaction ~@body)))

(defmacro session
  "Evaluate body in a reaction which is immediately disposed"
  [& body]
  `(let [rx# (reaction! ~@body)
         v# @rx#]
     (r/dispose! rx#)
     v#))

(defmacro with-session
  "Evaluates body, accumulating dependencies in session (for later disposal)"
  [session & body]
  `(let [session# ~session]
     (with-owner session#
       (binding [r/*captured-derefs* (volatile! r/init-derefs)]
         (let [val# (do ~@body)
               new-derefs# @r/*captured-derefs*]
           (doseq [producer# new-derefs#] (add-watch producer# session# (fn [& args#])))
           (r/set-derefs! session# (into (r/get-derefs session#) new-derefs#))
           val#)))))

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

(defmacro present? [name]
  (if (:ns &env)
    `(~'exists? ~name)
    `(.hasRoot (def ~name))))

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