(ns re-db.dev
  (:require [nextjournal.clerk :as clerk]
            [nextjournal.clerk.config :as config]
            [shadow.cljs.devtools.api :as shadow]))

(defn start
  {:shadow/requires-server true}
  []
  (shadow/watch :clerk)
  (swap! config/!resource->url merge {"/js/viewer.js" "http://localhost:8008/clerk/clerk.js"})
  (clerk/serve! {:browse? true}))

(comment
 (start)
 (clerk/clear-cache!))