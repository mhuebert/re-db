(ns re-db.core)

(defmacro fast-get-in
  "Compiled version of get-in, faster than `get-in` function"
  [m ks]
  (if-not (vector? ks)
    `(clojure.core/get-in ~m ~ks)
    `(-> ~m
         ~@(for [k ks]
             (list 'clojure.core/get k)))))

#_(defmacro db-log [& body]
    `(binding [~'re-db.core/*db-log* (atom {})
               ~'re-db.core/*prevent-notify* true]
       (let [value# (do ~@body)]
         {:tx-report @~'re-db.core/*db-log*
          :value value#})))