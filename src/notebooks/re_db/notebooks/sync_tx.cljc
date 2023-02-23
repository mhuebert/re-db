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
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.sync.entity-diff-1 :as entity-diff]
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

;; an atom of refs to expose, a map of ids to functions which return reactions.
;; refs are requested via vectors of the form [<id> & args].
(def !refs
  (let [$entity-fn (memo/fn-memo [id]
                     (db/bound-reaction conn
                       (db/get id)))
        $txs (memo/memoize entity-diff/txs)]
    (atom
     {:entity-1 (constantly ($entity-fn 1))
      :entity $entity-fn
      :diffs/entity-1 (constantly ($txs ($entity-fn 1)))
      :diffs/entity (comp $txs $entity-fn)})))

;; A websocket server (clj, runs on the jvm):
#?(:clj
   (def server
     (ws/serve :port 9062
               :handlers (merge
                          (sync/query-handlers
                           (memo/fn-memo [ref-id]
                             (let [[id & args] (if (sequential? ref-id)
                                                 ref-id
                                                 [ref-id])]
                               (apply (@!refs id) args))))
                          {:db/add! (fn [context e a v]
                                      (transact! [[:db/add e a v]]))}))))

;; A websocket channel (cljs, runs in the browser):
(show-cljs
  (def channel
    (ws/connect :port 9062
                :handlers (sync/result-handlers entity-diff/result-handlers))))

;; Show `:entity-1`
(show-cljs
  (let [result @(sync/$query channel :entity-1)]
    (render/inspect
     (seq (:value result result)))))

;; Show `:diffs/entity-1`
(show-cljs
  (let [result @(sync/$query channel :diffs/entity-1)]
    (render/inspect
     (seq (:value result result)))))


^{::clerk/visibility {:code :hide}}
(show-cljs
  [:button.p-2.rounded.bg-blue-100
   {:on-click #(sync/send channel [:db/add! 1 (rand-nth [:A :B]) (rand-int 100)])}
   "Modify entity 1"])

;; Show `[:entity 2]`
(show-cljs
  (let [result @(sync/$query channel [:entity 2])]
    (render/inspect
     (seq (:value result result)))))

;; Show `[:diffs/entity 2]`
(show-cljs
  (let [result @(sync/$query channel [:diffs/entity 2])]
    (render/inspect
     (seq (:value result result)))))


^{::clerk/visibility {:code :hide}}
(show-cljs
  [:button.p-2.rounded.bg-blue-100
   {:on-click #(sync/send channel [:db/add! 2 (rand-nth [:A :B]) (rand-int 100)])}
   "Modify entity 2"])


^{::clerk/visibility {:code :hide :result :hide}}
(memo/defn-memo $log [!ref n]
  (xf/transform !ref (keep identity) (xf/sliding-window n)))

;; Show a log of events:
^{::clerk/visibility {:code :hide}}
(show-cljs
  [:div.whitespace-pre-wrap.code.text-xs
   (with-out-str (pprint @($log (:!last-message channel) 10)))])