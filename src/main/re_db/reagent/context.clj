(ns re-db.reagent.context)

(defmacro bind-conn [conn body]
  `(~'re-db.api/with-conn ~conn
     [~'re-db.reagent.context/bind-conn
      ~'re-db.api/*current-conn*
      ~body]))