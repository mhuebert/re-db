(ns re-db.xform
  (:refer-clojure :exclude [into map])
  (:require [re-db.hooks :as hooks]
            [re-db.reactive :as r]
            [re-db.xform.reducers :as reducers]))

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
  [ref & xforms]
  (let [f (step (apply comp xforms))
        !value (volatile! nil)]
    (r/reaction
     (let [this r/*owner*]
       (hooks/use-effect
        (fn []
          (add-watch ref this (fn [_ _ _ _] (r/compute! this)))
          #(remove-watch ref this)))
       (let [next-val (r/peek ref)]
         (if (r/error? next-val)
           next-val
           (let [next-step (f next-val)]
             (case next-step
               ::no-op @!value
               ::done (do (remove-watch ref this) @!value)
               (vreset! !value next-step)))))))))

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
  (reducing-transducer reducers/before:after))

(def sliding-window (comp reducing-transducer reducers/sliding-window))

(comment

 (r/session
  (let [source (atom 1)
        before-after (transform source (before:after))]
    @before-after))

 (r/session
  (let [source (atom 0)
        before-after (transform source (before:after))]
    @before-after
    (swap! source inc)
    @before-after))


 (r/session
  (let [source (atom 0)
        pairs (transform source
                (before:after)
                (take 5)
                (into []))]
    (dotimes [_ 10]
      (swap! source inc))
    @pairs))

 (r/session
  (let [source (r/reaction (hooks/use-effect
                            (fn []
                              (prn :reaction/init)
                              #(prn :reaction/dispose)))
                           0)
        evens (transform:lazy source
                              (filter even?)
                              (take 3)
                              (into []))]
    (prn :----- 1)
    @evens
    (prn :----- 2)
    (dotimes [_ 10] (swap! source inc))
    (prn :----- 3)
    @evens
    ))

 )
