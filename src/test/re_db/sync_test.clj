(ns re-db.sync-test
  (:require [datomic.api :as dm]
            [re-db.integrations.datomic]

            [datahike.api :as dh]
            [re-db.integrations.datahike]

            [re-db.integrations.in-memory]

            [re-db.api :as re]
            [re-db.query :as q]
            [re-db.reactive :as r]
            [re-db.hooks :as hooks]
            [re-db.protocols :as rp]
            [re-db.subscriptions :as subs]
            [clojure.test :as test :refer [is are deftest testing]]
            [re-db.schema :as schema]
            [re-db.read :as read]
            [clojure.string :as str]))

(subs/clear-subscription-cache!)
(read/clear-listeners!)

(do


  (def databases
    [{:id :datomic
      :conn (dm/connect (doto "datomic:mem://foo5" dm/create-database))}
     (let [config {:store {:backend :file :path "/tmp/example"}}]
       {:id :datahike
        :config config
        :conn (do (try (dh/delete-database config) (catch Exception e))
                  (try (dh/create-database config) (catch Exception e))
                  (dh/connect config))})
     {:id :in-memory
      :conn (re/create-conn)}])


  (defn transact! [txs]
    (mapv (fn [{:keys [conn]}]
            (->> (rp/transact conn txs)
                 (read/handle-report! conn))) databases))

  (doseq [{:keys [conn]} databases]
    (rp/merge-schema conn {:movie/title
                           {:db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one
                            :db/unique :db.unique/identity}

                           :movie/genre
                           {:db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}

                           :movie/release-year
                           {:db/valueType :db.type/long
                            :db/cardinality :db.cardinality/one}

                           :movie/emotions
                           {:db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/many}

                           :emotion/name
                           {:db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one
                            :db/unique :db.unique/identity}

                           :movie/dominant-emotion
                           {:db/valueType :db.type/ref,
                            :db/cardinality :db.cardinality/one,}

                           :movie/opposite-emotion
                           {:db/valueType :db.type/ref,
                            :db/cardinality :db.cardinality/one,}}))
  (transact! (for [name ["sad"
                         "happy"
                         "angry"
                         "excited"
                         "tense"
                         "whatever"]]
               {:emotion/name name}))
  (transact!
   [{:movie/title "The Goonies"
     :movie/genre "action/adventure"
     :movie/release-year 1985
     :movie/emotions #{[:emotion/name "happy"]
                       {:emotion/name "excited"}}}
    {:movie/title "Commando"
     :movie/genre "thriller/action"
     :movie/release-year 1985
     :movie/emotions [[:emotion/name "happy"]
                      {:emotion/name "tense"}
                      {:emotion/name "excited"}]}
    {:movie/title "Repo Man"
     :movie/genre "punk dystopia"
     :movie/release-year 1984
     :movie/emotions #{[:emotion/name "happy"]
                       [:emotion/name "sad"]}
     :movie/dominant-emotion [:emotion/name "sad"]
     :movie/opposite-emotion [:emotion/name "happy"]}])


  (def dm-conn (-> databases (nth 0) :conn))
  (def dh-conn (-> databases (nth 1) :conn))
  (def mem-conn (-> databases (nth 2) :conn)))

(comment

 ;; `pull` returns {:db/id ...} maps for refs (when no keys are specified)


 (re/with-conn mem-conn
   (re/transact! [[:db/add
                   [:movie/title "Commando"]
                   :movie/emotions
                   [:emotion/name "sad"]]]))

 (re/with-conn -conn
   (->> (re/entity [:movie/title "Commando"])
       :movie/emotions
        (map :emotion/name)
       ))

 )

;; a query-function that uses the read/entity api:

(deftest db-queries

  (q/register :emo-movies
              (fn [emo-name]
                (->> (re/entity [:emotion/name emo-name])
                     :movie/_emotions
                     (mapv :movie/title)
                     set)))


  (let [sad-queries (mapv (fn [{:keys [id conn]}]
                            [id (q/query conn [:emo-movies "sad"])])
                          databases)
        toggle-emotion! #(let [sad? (re/with-conn (:conn (first databases))
                                      (contains? (->> (re/get [:movie/title "Commando"] :movie/emotions)
                                                      (into #{} (map :emotion/name)))
                                                 "sad"))]
                           (transact! [[(if sad? :db/retract :db/add)
                                        [:movie/title "Commando"]
                                        :movie/emotions
                                        [:emotion/name "sad"]]]))
        !result (atom {})]

    (doseq [[id q] sad-queries]
      (add-watch q ::watch (fn [_ _ _ new] (swap! !result update id conj new)))
      (r/reaction! (swap! !result update id conj @q)))


    (toggle-emotion!)

    (is (apply = (vals @!result)))

    (toggle-emotion!)

    (is (apply = (vals @!result)))

    ;; watching our query, printing the value


    (doseq [[_ q] sad-queries] (r/dispose! q))))

(let [[dm dh mem] (map :conn databases)]
  (re/with-conn dh
    (-> [:movie/title "Repo Man"]
        (re/pull '[*
                   (:movie/title :db/id true)
                   {:movie/dominant-emotion [(:emotion/name :db/id true)]}
                   {:movie/emotions [(:emotion/name :db/id true)]}
                   {:movie/opposite-emotion [(:emotion/name :db/id true)]}]))))

(deftest reads
  (let [[dm dh mem] (map :conn databases)]
    (are [expr]
      (= (re/with-conn dm expr)
         (re/with-conn dh expr)
         (re/with-conn mem expr))

      (-> [:movie/title "Repo Man"]
          (re/pull '[*
                     (:movie/title :db/id true)
                     {:movie/dominant-emotion [(:emotion/name :db/id true)]}
                     {:movie/emotions [(:emotion/name :db/id true)]}
                     {:movie/opposite-emotion [(:emotion/name :db/id true)]}])
          (update :movie/emotions set))

      (->> (re/pull [:movie/title "The Goonies"]
                    '[*])
           :movie/emotions
           (every? map?))

      (->> (re/pull [:movie/title "The Goonies"]
                    '[*])
           :movie/emotions
           (map :movie/title))

      )))

(deftest attribute-resolvers
  (binding [read/*attribute-resolvers* {:movie/title-lowercase
                                          (fn [{:keys [movie/title]}]
                                            (str/lower-case title))}]
    (doseq [conn (map :conn databases)]
      (re/with-conn conn
        (is (= "the goonies"
               (-> (re/pull [:movie/title "The Goonies"] [:movie/title-lowercase])
                   :movie/title-lowercase)))))))

(let [schema {:owner (merge schema/ref
                            schema/unique-value
                            schema/one)
              :name (merge schema/unique-value
                           schema/one
                           schema/string)}]

  (re/with-conn dm-conn
    (re/merge-schema! schema)
    #_(re/transact! [{:db/id "fred"
                     :name "Fred"}
                    {:db/id "ball"
                     :name "Ball"
                     :owner "fred"}])
    {:name "Fred"
     :_owner {:db/id "ball"}}
    (:_owner (dm/entity (dm/db dm-conn) [:name "Fred"]))
    (:_owner (dm/pull (dm/db dm-conn) '[:name :_owner] [:name "Fred"]))))


(comment

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
   @!log))

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