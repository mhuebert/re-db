(ns re-db.reagent)

(defmacro logged-read! [conn e a v expr]
  `(~'re-db.reagent/logged-read* ~conn ~e ~a ~v (fn [] ~expr)))