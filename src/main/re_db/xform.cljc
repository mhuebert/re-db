(ns re-db.xform
  (:refer-clojure :exclude [into map ->])
  (:require [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.util :as util]
            [re-db.xform.reducers :as reducers])
  #?(:cljs (:require-macros re-db.xform)))

(defn transform
  "Streams values from ref into a new ratom, applying any supplied xforms (transducers)"
  [ref & xforms]
  (r/reaction
    {:xf (apply comp xforms)}
    (hooks/use-deref ref)))

(defn map [f source]
  (r/reaction {:xf (clojure.core/map f)}
    (hooks/use-deref source)))

(util/sci-macro
  (defmacro -> [ref & forms]
    `(map (fn [x#] (clojure.core/-> x# ~@forms)) ~ref)))

(defn compr [rfn f]
  (fn
    ([] (rfn))
    ([acc] (rfn acc))
    ([acc x] (f acc x))))

(defn reducing-transducer
  "Given reducing function `f` and optional `init` value (default: nil),
   calls (f acc x) for each step, where `acc` is the last value returned
   by this function and x is the incoming value.

   An optional `emit` function wraps the value that
   is returned from the transducer (ie. so the internal accumulator
   can differ from what is visible)

   eg:
   (into [] (reducing-transducer conj) [1 2 3])
   => [[1] [1 2] [1 2 3]]"
  ([f] (reducing-transducer f (f) identity))
  ([f init] (reducing-transducer f init identity))
  ([f init emit]
   (let [inner-acc (volatile! init)]
     (fn [rfn]
       (compr rfn (fn [acc x]
                    (vswap! inner-acc f x)
                    (rfn acc (emit @inner-acc))))))))

(defn into
  ;; returns transducer, conj's values into init
  ([init] (reducing-transducer conj init))
  ([init source] (r/compute! (transform source (reducing-transducer conj init))))
  ([init xform source] (r/compute! (transform source (comp xform (reducing-transducer conj init))))))

(defn before:after
  "Stateful transducer, returns a before/after tuple for successive values, starting with nil."
  []
  (reducing-transducer reducers/before:after))

(def sliding-window (comp reducing-transducer reducers/sliding-window))