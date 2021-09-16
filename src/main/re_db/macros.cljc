(ns re-db.macros
  (:refer-clojure :exclude [partial]))

(defmacro defpartial
  "Defines a partially-applied function with static arities
  (to avoid dynamic dispatch and overhead of `partial`)

  Syntax is like `defn` with an options map containing :f,
  an expression containing `_` where args should be spliced in

  eg
  (defpartial add-to-1 {:f `(+ 1 _)} ([x]) ([x y]))"
  [name {:keys [f]} & arglists]
  (let [f (if (= 'quote (first f)) (second f))
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
