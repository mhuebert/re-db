(ns re-db.sync-notebook
  (:require [datomic.api :as dm]
            [re-db.integrations.datomic]
            [datahike.api :as dh]
            [re-db.integrations.datahike]
            [re-db.api :as re]
            [re-db.query :as q]
            [re-db.reactive :as r]
            [re-db.patterns :as patterns]
            [re-db.hooks :as hooks]))

(def dm-conn (dm/connect (doto "datomic:mem://foo5" dm/create-database)))
(def dh-cfg {:store {:backend :file :path "/tmp/example"}})
(def dh-conn (do
               #_(defonce _dh-db (dh/create-database dh-cfg))
               (dh/connect dh-cfg)))

;; TODO
;; "listen" to dm-conn and dh-conn


(defn transact! [txs]
  (->> @(dm/transact dm-conn txs)
       (patterns/handle-report! dm-conn))
  (->> (dh/transact dh-conn txs)
       (patterns/handle-report! dh-conn)))

(defn dm-db [] (dm/db dm-conn))
(defn dh-db [] @dh-conn)

(defn schema-ident
  "Helper function for setting up schema"
  [[attr type cardinality & {:as params}]]
  (merge {:db/ident attr
          :db/valueType (keyword "db.type" (name type))
          :db/cardinality (keyword "db.cardinality" (name cardinality))}
         params))

(transact! (map schema-ident [[:movie/title "string" :one {:db/unique :db.unique/identity}]
                              [:movie/genre "string" :one]
                              [:movie/release-year "long" :one]
                              [:movie/emotions "ref" :many]
                              [:emotion/name "string" :one {:db/unique :db.unique/identity}]
                              [:movie/dominant-emotion "ref" :one {:db/unique :db.unique/identity}]
                              [:movie/opposite-emotion "ref" :one {:db/unique :db.unique/identity}]]))

;; add some emotion entities...


(transact! (for [name ["sad"
                       "happy"
                       "angry"
                       "excited"
                       "tense"
                       "whatever"]]
             {:emotion/name name}))

;; add some movies...

(transact!
 [#:movie{:title "The Goonies"
          :genre "action/adventure"
          :release-year 1985
          :emotions #{[:emotion/name "happy"]
                      {:emotion/name "excited"}}}
  #:movie{:title "Commando"
          :genre "thriller/action"
          :release-year 1985
          :emotions [[:emotion/name "happy"]
                     {:emotion/name "tense"}
                     {:emotion/name "excited"}]}
  #:movie{:title "Repo Man"
          :genre "punk dystopia"
          :release-year 1984
          :emotions #{[:emotion/name "happy"]
                      [:emotion/name "sad"]}
          :dominant-emotion [:emotion/name "sad"]
          :opposite-emotion [:emotion/name "happy"]}])

;; a query-function that uses the read/entity api:

(q/register :emo-movies
  (fn [emo-name]
    (->> (re/entity [:emotion/name emo-name])
         :movie/_emotions
         (mapv :movie/title))))

(comment
 ;; client usage:
 (ns my.app
   (:require [re-db.sync.client :refer [query]]))

 (defn my-component []
   @(query [:emo-movies "happy"])))


(def dm-sad (q/query dm-conn [:emo-movies "sad"]))
(def dh-sad (q/query dh-conn [:emo-movies "sad"]))
(transact! [[:db/add
             [:movie/title "Commando"]
             :movie/emotions
             [:emotion/name "sad"]]])

(prn :current-sad-value @dm-sad)
(prn :current-sad-value @dh-sad)
(r/dispose! dh-sad)
(r/dispose! dm-sad)

(def dm-happy (q/query dm-conn [:emo-movies "happy"]))
(def dh-happy (q/query dh-conn [:emo-movies "happy"]))

;; watching our query, printing the value
(add-watch dm-happy :prn (fn [_ _ _ new] (prn :dm-happy-watch new)))
(add-watch dh-happy :prn (fn [_ _ _ new] (prn :dh-happy-watch new)))

(def dm-happy-rx (r/reaction! (prn :dm-happy-reaction @dm-happy)))
(def dh-happy-rx (r/reaction! (prn :dh-happy-reaction @dh-happy)))

;; modify the query result-set, observe results:
(do (transact! [[:db/add
                 [:movie/title "Repo Man"]
                 :movie/emotions
                 [:emotion/name "happy"]]])
    [@dm-happy
     @dh-happy])

(do (transact! [[:db/retract
                 [:movie/title "Repo Man"]
                 :movie/emotions
                 [:emotion/name "happy"]]])
    [@dm-happy
     @dh-happy])

(doseq [rx [dm-happy dh-happy dm-happy-rx dh-happy-rx]]
  (r/dispose! rx))

(prn :no-more-printing)
(transact! [[:db/add
             [:movie/title "Repo Man"]
             :movie/emotions
             [:emotion/name "happy"]]])

;; pull api
(q/once (dm-db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull [:movie/title
                     {:movie/emotions [:emotion/name]}
                     {:movie/dominant-emotion [:emotion/name]}
                     :movie/opposite-emotion])))

;; aliases
(q/once (dm-db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[(:movie/title :as :title)
                      {(:movie/emotions :as :emos) [(:emotion/name :as :name)]}])))

;; :id option - use as lookup ref
(q/once (dm-db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[(:movie/title :db/id true)])))

(q/once (dm-db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[*
                      {:movie/dominant-emotion [*]}
                      {:movie/opposite-emotion [:db/id]}])))

;; logged lookups

(q/once (dm-db)
        (q/av_ :movie/title "Repo Man"))

(q/once (dm-db)
        (q/_a_ :movie/title))

(q/once (dm-db)
        (q/ea_ [:movie/title "Repo Man"] :movie/dominant-emotion))

(q/capture-patterns (dm-db)
                    (q/av_ :movie/title "Repo Man"))

;; filtering api
(q/once (dm-db)
        (q/where ;; the first clause selects a group of entities
         [:movie/title "Repo Man"]
         ;; subsequent clauses filter the initial set of entities
         [:movie/dominant-emotion [:emotion/name "sad"]]))

(q/inspect-patterns (dm-db)
                    (->> (q/where [:movie/release-year 1985])
                         (mapv deref)
                         #_(q/pull [:movie/release-year :movie/title])))

;; TODO - wildcard
'(q/pull '[* {:sub/child [:name :birthdate]}])

(let [a (r/atom 0)
      my-query (q/make-query dm-conn #(doto (* @a 10) prn))]
  (swap! a inc)
  (swap! a inc)
  (r/dispose! my-query))

;; testing with-let in a reaction
(let [!log (atom [])
      log (fn [& args] (swap! !log conj args))
      a (r/atom 0)
      r (r/reaction!
         (hooks/with-let [_ (log :with-let/init @a)]
                         (doto @a (->> (log :with-let/body)))
                         (finally (log :with-let/finally))))]
  (swap! a inc)
  (swap! a inc)
  (log :with-let/value @r)
  (r/dispose! r)
  (swap! a inc)
  @!log)

(comment
 (let [a (r/atom 0)
       b (r/atom 0)
       effect (r/reaction!
               (hooks/use-effect (fn [] (prn :init) #(prn :dispose)) [@b])
               (prn :body @a))]
   (swap! a inc)
   (swap! a inc)
   (swap! b inc)
   (r/dispose! effect))

 (let [a (r/atom 0)
       b (r/atom 0)
       memo (r/reaction!
             (hooks/use-memo #(doto (rand-int 100) (->> (prn :init))) [@b])
             (prn :body)
             @a)]
   (swap! a inc)
   (swap! b inc)
   (r/dispose! memo)
   (swap! a inc) (swap! b inc)))
