(ns re-db.notebooks.index
  (:require [re-db.notebooks.local]
            [re-db.notebooks.sync-editscript]
            [re-db.notebooks.sync-simple]
            [re-db.notebooks.test]
            [re-db.notebooks.tx-reactive]
            [re-db.notebooks.xform]
            [nextjournal.clerk.viewer :as v]
            [re-db.reactive :as r]))


(v/reset-viewers! :default
                  (v/add-viewers v/default-viewers
                                 [{:pred #?(:clj  #(instance? clojure.lang.IDeref %)
                                            :cljs #(satisfies? IDeref %))
                                   :transform-fn (v/update-val
                                                  (fn [ideref]
                                                    (v/with-viewer :tagged-value
                                                                   {:tag "object"
                                                                    :value (vector (symbol (pr-str (type ideref)))
                                                                                   (r/peek ideref))})))}]))