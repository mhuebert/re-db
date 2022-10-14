(ns re-db.integrations-test
  (:require [datomic.api :as dm]
            [re-db.integrations.datomic]

            [datahike.api :as dh]
            [datalevin.core :as dl]
            [re-db.integrations.datahike]
            [re-db.integrations.datalevin]
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
            [clojure.string :as str]
            [clojure.walk :as walk]))

(subs/clear-subscription-cache!)
(read/clear-listeners!)

(defn noids [e]
  (walk/postwalk (fn [x]
                   (if (:db/id x)
                     (dissoc x :db/id)
                     x)) e))

(do
  (def db-uuid (random-uuid))
  (def mem-conn (re/create-conn))
  (def dm-conn (dm/connect (doto (str "datomic:mem://db-" db-uuid) dm/create-database)))
  (def dh-conn (let [config {:store {:backend :file :path "/tmp/example"}}]
                 (do (try (dh/delete-database config) (catch Exception e))
                     (try (dh/create-database config) (catch Exception e))
                     (dh/connect config))))
  (def dl-conn (dl/get-conn (str "/tmp/datalevin/db-" db-uuid) {}))

  (def databases
    [{:id :datomic :conn dm-conn}
     {:id :datahike :conn dh-conn}
     {:id :datalevin :conn dl-conn}
     {:id :in-memory :conn mem-conn}])


  (defn transact! [txs]
    (mapv (fn [{:keys [conn]}]
            (->> (rp/transact conn txs)
                 (read/handle-report! conn))) databases))

  (def initial-data [{:movie/title "The Goonies"
                      :movie/genre "action/adventure"
                      :movie/release-year 1985
                      :movie/emotions [[:emotion/name "happy"]
                                       {:emotion/name "excited"}]}
                     {:movie/title "Commando"
                      :movie/genre "thriller/action"
                      :movie/release-year 1985
                      :movie/emotions [[:emotion/name "happy"]
                                       {:emotion/name "tense"}
                                       {:emotion/name "excited"}]}
                     {:movie/title "Repo Man"
                      :movie/genre "punk dystopia"
                      :movie/release-year 1984
                      :movie/emotions [[:emotion/name "happy"]
                                       [:emotion/name "sad"]]
                      :movie/top-emotion [:emotion/name "sad"]}
                     {:movie/title "Mr. Bean"}])

  (doseq [{:keys [conn]} databases]
    (rp/merge-schema conn {:movie/title (merge schema/string
                                               schema/one
                                               schema/unique-id)

                           :movie/genre (merge schema/string
                                               schema/one)

                           :movie/release-year (merge schema/long
                                                      schema/one)
                           :movie/emotions (merge schema/ref
                                                  schema/many)
                           :emotion/name (merge schema/string
                                                schema/one
                                                schema/unique-id)
                           :movie/top-emotion (merge schema/ref
                                                     schema/one)
                           }))
  (transact! (for [name ["sad"
                         "happy"
                         "angry"
                         "excited"
                         "tense"
                         "whatever"]]
               {:emotion/name name}))
  (transact! initial-data)
  nil

  )


(deftest entity-reverse

  (doseq [conn (map :conn databases)]
    (re/with-conn conn
      (is (string? (:emotion/name (first (re/where [:emotion/name])))))
      (is (= 1 (count (:movie/_emotions (re/entity [:emotion/name "tense"])))))
      (is (= "Commando"
             (-> [:emotion/name "tense"]
                 re/entity
                 :movie/_emotions
                 first
                 :movie/title))))))

;; a query-function that uses the read/entity api:

(deftest db-queries

  (transact! initial-data)

  (q/register :emo-movies
    (fn [emo-name]
      (->> (re/entity [:emotion/name emo-name])
           :movie/_emotions
           (mapv :movie/title)
           set)))

  (r/session
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
       @(r/reaction (swap! !result update id conj @q)))


     (toggle-emotion!)

     (is (apply = (vals @!result)))

     (toggle-emotion!)

     (is (apply = (vals @!result)))

     ;; watching our query, printing the value


     (doseq [[_ q] sad-queries] (r/dispose! q)))))

(deftest reads

  (doseq [{:keys [conn]} databases]
    (rp/merge-schema conn {:owner (merge schema/ref
                                         schema/unique-id
                                         schema/one)
                           :person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)})
    (rp/transact conn [{:person/name "Fred"}])
    (rp/transact conn [{:owner [:person/name "Fred"]
                        :person/name "Ball"}]))

  (dl/update-schema dl-conn {:owner (merge schema/ref
                                           schema/unique-id
                                           schema/one)})

  (let [[dm-conn
         dh-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (re/with-conn dm-conn expr)
         (re/with-conn dh-conn expr)
         (re/with-conn dl-conn expr)
         (re/with-conn mem-conn expr))

      (->> [:movie/title "Repo Man"]
           (re/pull '[*
                      (:movie/title :db/id true)
                      {:movie/top-emotion [(:emotion/name :db/id true)]}
                      {:movie/emotions [(:emotion/name :db/id true)]}])
           (#(update % :movie/emotions set)))

      (into #{} (map :person/name) (:_owner (re/entity [:person/name "Fred"])))
      (into #{} (map :person/name) (:_owner (re/pull '[:person/name :_owner] [:person/name "Fred"])))

      (->> [:movie/title "Mr. Bean"]
           (re/pull '[:movie/emotions]))))

  (let [[dm-conn
         dh-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (re/with-conn dm-conn expr)
         (re/with-conn dh-conn expr)
         (re/with-conn dl-conn expr)
         (re/with-conn mem-conn expr))

      (->> [:movie/title "The Goonies"]
           (re/pull '[*])
           :movie/emotions
           (every? map?))

      (->> [:movie/title "The Goonies"]
           (re/pull '[*])
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
               (->> [:movie/title "The Goonies"]
                    (re/pull [:movie/title-lowercase])
                    :movie/title-lowercase)))))))



(comment

 ;; pull api
 (q/once (dm-db)
         (-> (q/entity [:movie/title "Repo Man"])
             (q/pull [:movie/title
                      {:movie/emotions [:emotion/name]}
                      {:movie/top-emotion [:emotion/name]}])))

 ;; aliases
 (q/once (dm-db)
         (-> (q/entity [:movie/title "Repo Man"])
             (q/pull '[(:movie/title :as :title)
                       {(:movie/emotions :as :emos) [(:emotion/name :as :person/name)]}])))

 ;; :id option - use as lookup ref
 (q/once (dm-db)
         (-> (q/entity [:movie/title "Repo Man"])
             (q/pull '[(:movie/title :db/id true)])))

 (q/once (dm-db)
         (-> (q/entity [:movie/title "Repo Man"])
             (q/pull '[*
                       {:movie/top-emotion [*]}])))

 ;; logged lookups

 (q/once (dm-db)
         (q/av_ :movie/title "Repo Man"))

 (q/once (dm-db)
         (q/_a_ :movie/title))

 (q/once (dm-db)
         (q/ea_ [:movie/title "Repo Man"] :movie/top-emotion))

 (q/capture-patterns (dm-db)
                     (q/av_ :movie/title "Repo Man"))

 ;; filtering api
 (q/once (dm-db)
         (q/where ;; the first clause selects a group of entities
          [:movie/title "Repo Man"]
          ;; subsequent clauses filter the initial set of entities
          [:movie/top-emotion [:emotion/name "sad"]]))

 (q/inspect-patterns (dm-db)
                     (->> (q/where [:movie/release-year 1985])
                          (mapv deref)
                          #_(q/pull [:movie/release-year :movie/title])))

 ;; TODO - wildcard
 '(q/pull '[* {:sub/child [:person/name :birthdate]}])

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

(comment
 (rp/merge-schema dm-conn {:person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)
                           :pets (merge schema/ref
                                        schema/many)})
 (rp/transact dm-conn [{:db/id -1
                        :person/name "Mr. Porcupine"
                        :_pets {:db/id -2
                                :person/name "Sally"
                                :hair-color "brown"}}])
 )

(deftest ident-refs
  (is
   (apply =
          (for [{:keys [id conn]} databases]
            (re/with-conn conn
              (re/merge-schema! {:favorite-attribute (merge schema/ref
                                                            schema/one)
                                 :person/name {}
                                 :id (merge schema/unique-id
                                            schema/string
                                            schema/one)})
              (re/transact! [{:db/ident :person/name}
                             {:id "A"
                              :favorite-attribute :person/name}])
              [(-> (re/entity [:id "A"]) :favorite-attribute)
               (re/pull '[:favorite-attribute] [:id "A"])
               (re/pull '[*] [:id "A"])])))))

(deftest idents-in-where
  (re/with-conn dm-conn
    (re/merge-schema! {:service/commission (merge schema/ref schema/one)
                       :system/id (merge schema/unique-id schema/one schema/string)
                       :commission/name (merge schema/string schema/one)
                       :service/phase (merge schema/ref schema/one)
                       :phase/entry {}})
    (re/transact! [{:db/id "1"
                    :system/id "S"
                    :service/phase :phase/entry
                    :service/commission "2"}
                   {:db/id "2"
                    :system/id "C"
                    :commission/name "A commission"}])
    (is (some? (first (re/where [[:service/phase :phase/entry]
                                 [:service/commission [:system/id "C"]]]))))
    (is (some? (first (re/where [[:service/commission [:system/id "C"]]
                                 [:service/phase :phase/entry]]))))))

;; issues
;; - support for keywords-as-refs in re-db (? what does this even mean)
;; - support for resolving idents