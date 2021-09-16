(ns re-db.test-helpers)

(defmacro throws [& body]
  (let [message (when (string? (last body)) (last body))
        body (cond-> body
                     message (drop-last))]
    (cond-> `(~'cljs.test/is (~'thrown? ~'js/Error ~@body))
            message (concat (list message)))))

(defmacro bench [label & pairs]
  (let [suite (with-meta (symbol "suite") {:tag 'js})]
    `(let [~suite (new ~'re-db.test-helpers/Suite)]
       (prn ~(str "---- benchmarking " label))
       (-> ~suite
           ~@(for [[label f] (partition 2 pairs)]
               `(.add ~label ~f))
           (.on "cycle" (comp ~'js/console.log str (~'applied-science.js-interop/get :target)))
           (.run)))))