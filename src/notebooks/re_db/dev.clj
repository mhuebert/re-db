(ns re-db.dev
  (:require [nextjournal.clerk :as clerk]
            [nextjournal.clerk.config :as config]
            [shadow.cljs.devtools.api :as shadow]))

(defn start
  {:shadow/requires-server true}
  []
  (compile 're-db.scratch.Suspension)
  (shadow/watch :clerk)
  (swap! config/!resource->url merge {"/js/viewer.js" "http://localhost:8008/clerk/clerk.js"})
  (clerk/serve! {:browse? true
                 :watch-paths ["src/notebooks/re_db/notebooks"]
                 :show-filter-fn #(re-find #"notebooks/[^/]+\.clj\w?" %)})

  (eval '(do (in-ns 'nextjournal.clerk.analyzer)
            (defn hash-codeblock [->hash {:as codeblock :keys [hash form id deps vars]}]
              (let [->hash' (if (and (not (ifn? ->hash)) (seq deps))
                              (binding [*out* *err*]
                                (println "->hash must be `ifn?`" {:->hash ->hash :codeblock codeblock})
                                identity)
                              ->hash)
                    hashed-deps (into #{} (map ->hash') deps)]
                (sha1-base58 (binding [*print-length* nil]
                               (pr-str (set/union (conj hashed-deps id)
                                                  vars)))))))))

(comment
 (start)
 (clerk/clear-cache!)

 (do (in-ns 'nextjournal.clerk.analyzer)
     (defn hash-codeblock [->hash {:as codeblock :keys [hash form id deps vars]}]
       (let [->hash' (if (and (not (ifn? ->hash)) (seq deps))
                       (binding [*out* *err*]
                         (println "->hash must be `ifn?`" {:->hash ->hash :codeblock codeblock})
                         identity)
                       ->hash)
             hashed-deps (into #{} (map ->hash') deps)]
         (sha1-base58 (binding [*print-length* nil]
                        (pr-str (set/union (conj hashed-deps id)
                                           vars))))))))