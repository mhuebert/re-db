

(require '[nextjournal.clerk :as clerk])

;; or let Clerk watch the given `:paths` for changes
(clerk/serve! {:watch-paths ["src/notebooks"]
               #_#_:browse? true})