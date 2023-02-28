(ns re-db.react
  (:require #?(:cljs ["react" :as react])
            [re-db.reactive :as r]
            [re-db.macros :as rm]
            [re-db.impl.hooks :as -hooks])
  #?(:cljs (:require-macros re-db.react)))

(defn use-effect [f deps] #?(:cljs (react/useEffect f deps)))
(defn use-memo [f deps] #?(:cljs (react/useMemo f deps)))
(defn use-reducer [f init] #?(:cljs (react/useReducer f init)))

(deftype Owner [^:volatile-mutable derefs ^:volatile-mutable hooks update!]
  r/IReactiveFunction
  (computes? [_] false)
  (compute! [_] (update!))
  r/ITrackDerefs
  (get-derefs [_] derefs)
  (set-derefs! [this new-derefs] (set! derefs new-derefs) this)
  -hooks/IHook
  (get-hooks [_] hooks)
  (set-hooks! [_ new-hooks] (set! hooks new-hooks) new-hooks)
  (get-hook [_ i] (nth hooks i nil))
  (set-hook! [_ i new-hook] (set! hooks (assoc hooks i new-hook)) new-hook))

(defmacro use-reactive
  [& body]
  `(let [[_# update!#] (~'re-db.react/use-reducer inc 0)
         owner# (~'re-db.react/use-memo
                 (fn [] (->Owner r/init-derefs -hooks/init-hooks update!#))
                 (cljs.core/array))]
     ;; todo - check consequences of adding deref watches during render
     ;;        (we can move that to an effect but then we can get bugs where
     ;;         we don't update when we should)
     (rm/with-owner owner#
       (rm/with-hook-support!
        (rm/with-deref-capture! owner#
          (~'re-db.react/use-effect
           (fn []
             (fn []
               (r/handle-new-derefs! owner# nil)
               (-hooks/dispose-hooks! owner#)))
           (cljs.core/array))
          ~@body)))))