(ns re-db.notebooks.tools.sync
  (:require [re-db.sync :as sync]))

(defn make-handlers [& {:keys [result-handlers
                                resolve-refs]
                         :or {result-handlers {}
                              resolve-refs {}}}]
  {::sync/result
   (fn [_ id target]
     (sync/transact-result result-handlers id target))
   ::sync/watch
   (fn [{:keys [channel]} ref-id]
     (if-let [!ref (resolve-refs ref-id)]
       (sync/watch channel ref-id !ref)
       (println "No ref found" ref-id)))
   ::sync/unwatch
   (fn [{:as context :keys [channel]} ref-id]
     (if-let [!ref (resolve-refs ref-id)]
       (sync/unwatch channel !ref)
       (println "No ref found" ref-id)))})

(defn handle-message [handlers context mvec]
  (if-let [handler (handlers (mvec 0))]
    (apply handler context (rest mvec))
    (println (str "Handler not found for: " mvec))))