(ns re-db.sync.registry)

;; To "handle" message vectors, we'll associate identifiers (the first position of the mvec)
;; with handler functions in an atom that we can `register` to when adding support for
;; new message types.

(defonce !handlers (atom {}))

(defn register [& {:as handlers}] (swap! !handlers merge handlers))

;; A `handle` function resolves an mvec to its handler function, then calls the function,
;; passing in the mvec and a context map.

(defn handle [context mvec]
  (let [handler ((:handlers context @!handlers) (mvec 0))]
    (if handler
      (apply handler (assoc context :mvec mvec) (rest mvec))
      (println (str "Handler not found for: " mvec)))))