(ns re-db.impl.hooks)

(defprotocol IHook
  "for composable side-effects within reactions (modeled on React hooks).
   See re-db.hooks for the api."
  (get-hooks [this])
  (set-hooks! [this hooks])
  (get-hook [this i])
  (set-hook! [this i new-hook] "Sets and returns new-hook"))

(defn update-hook!
  "Updates and returns hook"
  [this i f & args]
  (set-hook! this i (apply f (get-hook this i) args)))

(def ^:dynamic *hook-i*)

(defn get-next-hook! [owner expected-type & [init-fn]]
  (let [i (vswap! *hook-i* inc)
        hook (get-hook owner i)]
    (when (and hook (not (= (:hook/type hook) expected-type)))
      (throw (ex-info (str "expected hook of type " expected-type ", but found " (:hook/type hook) ". "
                           "A reaction must always call the same hooks in the same order on every evaluation.")
                      {:hook hook
                       :reaction-meta (meta owner)})))
    [i
     (or hook
         (set-hook! owner i (merge (if init-fn {:hook/value (init-fn)} {})
                                   {:hook/type expected-type})))
     (nil? hook)]))

(def init-hooks [])

(defn dispose-hooks! [this]
  (let [hooks (get-hooks this)]
    (doseq [hook hooks
            :let [dispose (:hook/dispose hook)]]
      (when dispose (dispose)))
    (set-hooks! this init-hooks)))