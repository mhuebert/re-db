(ns re-db.notebooks.tools.sync
  (:require [re-db.sync :as sync]))

(defn make-handlers [& {:keys [result-handlers
                               resolve-refs]
                        :or {result-handlers {}
                             resolve-refs {}}}]
  {::sync/result
   (fn [_ [id result]]
     (sync/transact-result result-handlers id result))
   ::sync/watch
   (fn [{:keys [channel]} query-vec]
     (if-let [!ref (resolve-refs query-vec)]
       (sync/watch channel query-vec !ref)
       (println "No ref found" query-vec)))
   ::sync/unwatch
   (fn [{:as context :keys [channel]} query-vec]
     (if-let [!ref (resolve-refs query-vec)]
       (sync/unwatch channel !ref)
       (println "No ref found" query-vec)))})

(defn handle-message [handlers context message]
  (if-let [handler (handlers (message 0))]
    (apply handler context (rest message))
    (println (str "Handler not found for: " message))))