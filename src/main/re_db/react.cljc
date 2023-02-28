(ns re-db.react
  (:require #?(:cljs ["react" :as react])
            [re-db.reactive :as r]
            [re-db.macros :as rm]
            [re-db.impl.hooks :as -hooks])
  #?(:cljs (:require-macros re-db.react)))


(defn useEffect [f deps] #?(:cljs (react/useEffect f deps)))
(defn useMemo [f deps] #?(:cljs (react/useMemo f deps)))
(defn useReducer [f init] #?(:cljs (react/useReducer f init)))

(deftype Owner [^:volatile-mutable derefs ^:volatile-mutable hooks update!]
  r/IReactiveFunction
  (computes? [_] false)
  (compute [this] this)
  (compute! [_] (update!))
  r/ITrackDerefs
  (get-derefs [_] derefs)
  (set-derefs! [this new-derefs] (set! derefs new-derefs) this))

(defmacro use-derefs
  [& body]
  `(let [[_# update!#] (~'re-db.react/useReducer inc 0)
         owner# (~'re-db.react/useMemo
                 (fn [] (->Owner r/init-derefs -hooks/init-hooks update!#))
                 (cljs.core/array))]
     ;; todo - check consequences of adding deref watches during render
     ;;        (we can move that to an effect but then we can get bugs where
     ;;         we don't update when we should)
     (rm/with-deref-capture! owner#
       (~'re-db.react/useEffect
        (fn []
          (fn []
            (r/handle-new-derefs! owner# nil)))
        (cljs.core/array))
       ~@body)))