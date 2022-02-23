(ns re-db.reagent)

(defmacro logged-read! [conn e a v expr]
  (if (:ns &env)
    `(~'re-db.reagent/logged-read* ~conn ~e ~a ~v (fn [] ~expr))
    expr))