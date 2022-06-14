(ns re-db.xform
  (:refer-clojure :exclude [map into])
  (:require [re-db.reactive :as r]))

(defn step [xform]
  (let [rfn (xform conj)
        done? (volatile! false)]
    (fn [x]
      (if @done?
        ::done
        (let [acc (rfn [] x)
              is-reduced? (reduced? acc)
              _ (when is-reduced? (vreset! done? true))
              acc (if is-reduced?
                    (rfn @acc)
                    acc)]
          (case (count acc)
            0 ::no-op
            1 (first acc)
            acc))))))

(defn transform
  "Streams values from ref into a new ratom, applying any supplied xforms (transducers)"
  [source & xforms]
  (let [key (gensym "stream")
        out (r/atom nil)
        f (step (apply comp xforms))
        handle-value (fn [value]
                       (let [v (f value)]
                         (case v
                           ::no-op nil
                           ::done (remove-watch source key)
                           (reset! out v))))]
    (handle-value @source)
    (add-watch source key (fn [_ _ _ value] (handle-value value)))
    (doto out (r/add-on-dispose! (fn [_] (remove-watch source key))))))

(defn map [f source]
  (transform source (clojure.core/map f)))

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
  ([init source] (transform source (reducing-transducer conj init)))
  ([init xform source] (transform source (comp xform (reducing-transducer conj init)))))

(defn before:after
  "Stateful transducer, returns a before/after tuple for successive values, starting with nil."
  []
  (reducing-transducer (fn
                         ([] [])
                         ([acc x] (conj (empty acc) (second acc) x)))))

(def sliding-window (comp reducing-transducer
                          (fn sliding-window [size]
                            (fn
                              ([] ())
                              ([acc] acc)
                              ([acc x] (cons x (take (dec size) acc)))))))

(comment

 (r/session
  (let [source (atom 1)
        before-after (transform source (before:after))]
    @before-after))

 (r/session
  (let [source (atom 1)
        before-after (transform source (before:after))]
    (reset! source 2)
    @before-after))


 (r/session
  (let [source (atom 1)
        pairs (transform source
                           (before:after)
                           (take 5)
                           (into []))]
    (dotimes [n 10]
      (reset! source n))
    @pairs))

 (r/session
  (let [source (atom 0)
        evens (transform source
                         (filter even?)
                         (take 3)
                         (into []))]
    (dotimes [n 10] (reset! source (inc n)))
    @evens)))