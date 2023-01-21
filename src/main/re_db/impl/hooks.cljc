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

(defn get-next-hook! [owner expected-type]
  (let [i (vswap! *hook-i* inc)
        hook (get-hook owner i)]
    (when hook
      (assert (= (:type (meta hook)) expected-type)
              (str "expected hook of type " expected-type ", but found " (:type hook) ". "
                   "A reaction must always call the same hooks in the same order on every evaluation.")))
    (or hook
        (set-hook! owner i (with-meta {} {:type expected-type})))))

(defn fresh? [hook] (empty? hook))

(def init-hooks [])

(defn dispose-hooks! [this]
  (let [hooks (get-hooks this)]
    (set-hooks! this init-hooks)
    (doseq [hook hooks
            :let [dispose (:dispose hook)]]
      (when dispose (dispose)))))