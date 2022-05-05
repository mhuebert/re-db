(ns re-db.sync-notebook
  (:require [datomic.api :as d]
            [nextjournal.clerk :as-alias clerk]
            [re-db.reactive.query :as q]
            [re-db.reactive :as r]
            [re-db.reactive.hooks :as hooks]))

^{::clerk/visibility :fold}
(do
  (def conn (d/connect (doto "datomic:mem://foo5" d/create-database)))

  (defn transact! [txs] @(d/transact conn txs))

  (defn db [] (d/db conn))

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
                                [:movie/opposite-emotion "ref" :one {:db/unique :db.unique/identity}]])))

;; add some emotion entities...


(transact! (for [name ["sad"
                       "happy"
                       "angry"
                       "excited"
                       "tense"]]
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
    (->> (q/entity [:emotion/name emo-name])
         :movie/_emotions
         (mapv :movie/title))))

(comment
 ;; client usage:
 (ns my.app
   (:require [re-db.sync.client :refer [query]]))

 (defn my-component []
   @(query [:emo-movies "happy"])))


(def sad (q/query conn [:emo-movies "sad"]))
(transact! [[:db/add
             [:movie/title "Commando"]
             :movie/emotions
             [:emotion/name "sad"]]])
(prn :current-sad-value @sad)
(r/dispose! sad)

(def happy (q/query conn [:emo-movies "happy"]))
happy ;; a Query instance
@happy ;; the value

;; watching our query, printing the value
(add-watch happy :prn (fn [_ _ _ new] (prn :happy-watch new)))
(def happy-rx (r/reaction! (prn :happy-reaction @happy)))

;; modify the query result-set, observe results:
(do (transact! [[:db/add
                 [:movie/title "Repo Man"]
                 :movie/emotions
                 [:emotion/name "happy"]]])
    @happy)

(do (transact! [[:db/retract
                 [:movie/title "Repo Man"]
                 :movie/emotions
                 [:emotion/name "happy"]]])
    @happy)

(r/dispose! happy-rx)
(r/dispose! happy)

(prn :no-more-printing)
(transact! [[:db/add
             [:movie/title "Repo Man"]
             :movie/emotions
             [:emotion/name "happy"]]])

;; pull api
(q/once (db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull [:movie/title
                     {:movie/emotions [:emotion/name]}
                     {:movie/dominant-emotion [:emotion/name]}
                     :movie/opposite-emotion])))

;; aliases
(q/once (db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[(:movie/title :as :title)
                      {(:movie/emotions :as :emos) [(:emotion/name :as :name)]}])))

;; :id option - use as lookup ref
(q/once (db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[(:movie/title :db/id true)])))

(q/once (db)
        (-> (q/entity [:movie/title "Repo Man"])
            (q/pull '[*
                      {:movie/dominant-emotion [*]}
                      {:movie/opposite-emotion [:db/id]}])))

;; logged lookups

(q/once (db)
        (q/av_ :movie/title "Repo Man"))

(q/once (db)
        (q/_a_ :movie/title))

(q/once (db)
        (q/ea_ [:movie/title "Repo Man"] :movie/dominant-emotion))

(q/capture-patterns (db)
                    (q/av_ :movie/title "Repo Man"))

;; filtering api
(q/once (db)
        (q/where ;; the first clause selects a group of entities
         [:movie/title "Repo Man"]
         ;; subsequent clauses filter the initial set of entities
         [:movie/dominant-emotion [:emotion/name "sad"]]))

(q/inspect-patterns (db)
                    (->> (q/where [:movie/release-year 1985])
                         (mapv deref)
                         #_(q/pull [:movie/release-year :movie/title])))

;; TODO - wildcard
'(q/pull '[* {:sub/child [:name :birthdate]}])

(let [a (r/atom 0)
      my-query (q/make-query conn #(doto (* @a 10) prn))]
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
