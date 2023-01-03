(ns re-db.notebooks.sync-tx
  (:require [clojure.pprint :refer [pprint]]
            [mhuebert.clerk-cljs :refer [show-cljs]]
            [nextjournal.clerk :as-alias clerk]
            [re-db.api :as db]
            [re-db.in-memory :as mem]
            [re-db.integrations.in-memory]
            [re-db.integrations.reagent]
            [re-db.memo :as memo]
            [re-db.notebooks.tools.websocket :as ws]
            [re-db.query :as q]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            #?(:cljs [nextjournal.clerk.render :as render])))

;; create a db connection. We'll use a re-db in-memory db here but you could also use
;; datomic or datalevin.
(defonce conn (mem/create-conn))

;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (->> (mem/transact! conn txs)
       (read/handle-report! conn)))

;; an atom of ref-resolvers. To watch a query we'll pass in a vector beginning with a query-id,
;; followed by (optional) arguments.
(def !ref-resolvers (atom {:entity
                           (memo/fn-memo [id]
                             (q/reaction conn {:value (db/get id)}))}))

;; A websocket server (clj, runs on the jvm):
#?(:clj
   (def server
     (ws/serve :port 9062
               :handlers (merge
                          (sync/watch-handlers :resolve-ref
                                               (fn [[id & args]]
                                                 (apply (@!ref-resolvers id) args)))
                          {:db/add! (fn [context e a v]
                                      (transact! [[:db/add e a v]]))}))))

;; A websocket channel (cljs, runs in the browser):
(show-cljs
  (def channel
    (ws/connect :port 9062
                :handlers (sync/watch-handlers))))


;; Show the result of watching `:entity`  of id `1` (as exposed in `!ref-resolvers`):
(show-cljs
  (let [result @(sync/$watch channel [:entity 1])]
    (render/inspect
     (or (:value result) result))))

;; Modify the list:
(show-cljs
  [:button.p-2.rounded.bg-blue-100
   {:on-click #(sync/send channel [:db/add! 1 (rand-nth [:a :b]) (rand-int 100)])}
   "Attr, update!"])

(memo/defn-memo $log [!ref n]
  (xf/transform !ref (keep identity) (xf/sliding-window n)))

;; Show a log of events:
(show-cljs
  [:div.whitespace-pre-wrap.code.text-xs
   (with-out-str (pprint @($log (:!last-message @channel) 10)))])

;; TODO
;;
;; entity serialization
;; diffs