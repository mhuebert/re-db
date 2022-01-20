(ns re-db.clerk
  (require '[nextjournal.clerk :as clerk]))

(defn serve! []
  (clerk/serve! {:watch-paths ["src/notebooks"]
                 :browse? true}))