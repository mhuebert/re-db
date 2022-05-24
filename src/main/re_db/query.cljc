(ns re-db.query
  (:require [re-db.protocols :as rp]
            [re-db.patterns :refer [*db* *conn*]]
            [re-db.reactive :as r]
            [re-db.subscriptions :as subs]
            [re-db.util :as u]))

(defprotocol ITimeTravel
  (as-of [this db]))

(u/support-clj-protocols
  (deftype Query [conn ^:volatile-mutable f ratom ^:volatile-mutable derefs ^:volatile-mutable hooks]
    r/IHook
    (get-hooks [this] hooks)
    (set-hooks! [this new-hooks] (set! hooks new-hooks))
    IDeref
    (-deref [this]
      (assert (not= ::disposed (r/peek ratom)) "Cannot deref a disposed query")
      @ratom)
    r/ICaptureDerefs
    (get-derefs [this] derefs)
    (set-derefs! [this new-derefs] (set! derefs new-derefs))
    ITimeTravel
    (as-of [this db]
      (try {:value (binding [*db* db]
                     (r/session (f)))}
           (catch #?(:clj Exception :cljs js/Error) e
             (prn :error-invalidating-query (ex-message e))
             {:error (ex-message e)})))
    r/ICompute
    (-set-function! [this new-f] (set! f new-f))
    (invalidate! [this]
      (let [;; recompute value of query, recording patterns
            new-value (try {:value (binding [*conn* conn]
                                     (r/with-owner this
                                       (r/capture-derefs!
                                        (r/support-hooks!
                                         (f)))))}
                           (catch #?(:clj Exception :cljs js/Error) e
                             (prn :error-invalidating-query (ex-message e))
                             {:error (ex-message e)}))
            changed? (not= new-value (r/peek ratom))]
        (when changed?
          (reset! ratom new-value))
        (r/peek ratom)))
    r/IDispose
    (get-dispose-fns [this] (r/get-dispose-fns ratom))
    (set-dispose-fns! [this dispose-fns] (r/set-dispose-fns! ratom dispose-fns))

    ;; clients should be added using add-watch, and removed upon disconnection.
    IWatchable
    (-add-watch [this key watch-fn] (add-watch ratom key watch-fn) this)
    (-remove-watch [this key] (remove-watch ratom key) this)))

(defn make-query
  [conn f]
  (let [state (r/atom nil)
        query (->Query conn
                       f
                       state
                       r/empty-derefs
                       r/empty-hooks)]
    (r/add-on-dispose! state (fn [_] (r/dispose-derefs! query)))
    (r/invalidate! query)
    query))

(defn register
  "Defines a reactive query function which tracks read-patterns (e-a-v) and recomputes when
   matching datoms are transacted to a Datomic connection (passed to the subscription/sub
   functions below)."
  [id f]
  (subs/register id
    (fn [conn args]
      (make-query conn #(apply f args)))
    (fn [conn args]
      #(apply f args))))

(defn patterns [q] (->> (r/get-derefs q)
                        (keep (comp :pattern meta))))

(defn query [conn qvec]
  (subs/subscription [(first qvec) conn (rest qvec)]))