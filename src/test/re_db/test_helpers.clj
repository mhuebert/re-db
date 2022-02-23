(ns re-db.test-helpers)

(defmacro throws [& body]
  (let [message (when (string? (last body)) (last body))
        body (cond-> body
                     message (drop-last))]
    (cond-> `(~(if (:ns &env) 'cljs.test/is 'clojure.test/is)
              (~'thrown? ~(if (:ns &env) 'js/Error 'Exception)
               ~@body))
            message (concat (list message)))))

(defmacro bench [label & pairs]
  (let [suite (with-meta (symbol "suite") {:tag 'js})]
    `(let [~suite (new ~'re-db.test-helpers/Suite)]
       (~'js/console.group (str ~label))
       (-> ~suite
           ~@(for [[label f] (partition 2 pairs)]
               `(.add ~label ~f))
           (.on "cycle" (comp ~'js/console.log str (~'applied-science.js-interop/get :target)))
           (.run))
       (~'js/console.groupEnd))))