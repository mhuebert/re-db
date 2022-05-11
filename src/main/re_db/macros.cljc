(ns re-db.macros
  (:require [re-db.reactive :as-alias r])
  #?(:cljs (:require-macros re-db.macros)))

(defmacro set-swap! [sym f & args]
  `(set! ~sym (~f ~sym ~@args)))

(defmacro with-owner [owner & body]
  `(binding [r/*owner* ~owner] ~@body))

(defmacro with-deref-capture! [& body]
  `(binding [r/*captured-derefs* (volatile! r/empty-derefs)]
     (let [val# (do ~@body)
           new-derefs# @r/*captured-derefs*]
       (r/handle-new-derefs! r/*owner* new-derefs#)
       val#)))

(defmacro without-deref-capture [& body]
  `(binding [r/*captured-derefs* nil] ~@body))

(defmacro with-hook-support! [& body]
  `(binding [r/*hook-i* (volatile! -1)]
     ~@body))

(defmacro reaction
  "Returns a derefable reactive source based on body. Re-evaluates body when any dependencies (captured derefs) change. Lazy."
  [& body]
  `(r/make-reaction (fn [] ~@body)))

(defmacro reaction!
  "Eager version of reaction"
  [& body]
  `(doto (r/make-reaction (fn [] ~@body)) r/invalidate!))

;; for dev - only adds derefs
(defmacro with-session
  "Evaluates body, accumulating dependencies in session (for later disposal)"
  [session & body]
  `(let [session# ~session]
     (with-owner session#
       (binding [r/*captured-derefs* (volatile! r/empty-derefs)]
         (let [val# (do ~@body)
               new-derefs# @r/*captured-derefs*]
           (doseq [producer# new-derefs#] (add-watch producer# session# (fn [& args#])))
           (r/set-derefs! session# (into (r/get-derefs session#) new-derefs#))
           val#)))))

(defmacro session
  "[] - returns a session.
   [& body] - Evaluates body in a reactive session which is immediately disposed"
  ([] `(~'r/make-session))
  ([& body]
   `(let [s# (r/make-session)
          v# (with-session s# ~@body)]
      (r/dispose! s#)
      v#)))


(defmacro defpartial
  "Defines a partially-applied function with static arities
  (to avoid dynamic dispatch and overhead of `partial`)

  Syntax is like `defn` with an options map containing :f,
  an expression containing `_` where args should be spliced in

  eg
  (defpartial add-to-1 {:f `(+ 1 _)} ([x]) ([x y]))"
  [name {:keys [f]} & arglists]
  (let [f (cond-> f
                  (and (list? f) (= 'quote (first f)))
                  second)
        f (if  (symbol? f) (list f '_) f)
        make-call (fn [argv]
                    (seq (reduce (fn [args x]
                                   (if (= x '_)
                                     (into args argv)
                                     (conj args x))) [] f)))]
    `(defn ~name
       ~@(for [argv arglists
               :let [argv (if (list? argv) (first argv) argv)]]
           (list argv (make-call argv))))))

(comment
 '(defpartial f {:f a/f
                 :args [*db*]}
    ([a])
    ([a b]))
 '(fn f
    ([a] (a/f a *db*))
    ([a b] (a/f a b *db*))))

(macroexpand-1
 '(re-db.macros/defpartial
    get {:f '(read/get *db* _)}
    ([id attr])
    ([id attr not-found])))