(ns re-db.integrations-test
  (:require [taoensso.tufte :as tufte :refer (defnp p profiled profile)]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [clojure.walk :as walk]
            [datalevin.core :as dl]
            [datomic.api :as dm]
            [datascript.core :as ds]
            [re-db.api :as db]
            [re-db.hooks :as hooks]
            [re-db.in-memory :as mem]
            [re-db.integrations.datalevin]
            [re-db.integrations.datomic]
            [re-db.integrations.in-memory]
            [re-db.memo :as memo]
            [re-db.triplestore :as ts]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.schema :as schema]))


(swap! read/!listeners empty)

(defn noids [e]
  (walk/postwalk (fn [x]
                   (if (:db/id x)
                     (dissoc x :db/id)
                     x)) e))

(do
  (def db-uuid (random-uuid))
  (def mem-conn (mem/create-conn))
  (def dm-conn (dm/connect (doto (str "datomic:mem://db-" db-uuid) dm/create-database)))
  (def dl-conn (dl/get-conn (str "/tmp/datalevin/db-" db-uuid) {}))

  (def databases
    [{:id :datomic :conn dm-conn}
     {:id :datalevin :conn dl-conn}
     {:id :in-memory :conn mem-conn}])

  (def the-schema {:movie/title (merge schema/string
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
                   })

  (defn transact! [txs]
    (mapv
     #(read/transact! (:conn %) txs)
     databases))

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
    (ts/merge-schema conn the-schema))
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
    (db/with-conn conn
      (is (string? (:emotion/name (first (db/where [:emotion/name])))))
      (is (= 1 (count (:movie/_emotions (db/entity [:emotion/name "tense"])))))
      (is (= "Commando"
             (-> [:emotion/name "tense"]
                 db/entity
                 :movie/_emotions
                 first
                 :movie/title))))))

;; a query-function that uses the read/entity api:



(deftest db-queries

  (memo/defn-memo $emo-movies [conn emo-name]
    (db/bound-reaction conn
      (->> (db/entity [:emotion/name emo-name])
           :movie/_emotions
           (mapv :movie/title)
           set)))

  (transact! initial-data)

  (r/session
   (let [sad-queries (mapv (fn [{:keys [id conn]}]
                             [id ($emo-movies conn "sad")])
                           databases)
         toggle-emotion! #(let [sad? (db/with-conn (:conn (first databases))
                                       (contains? (->> (db/get [:movie/title "Commando"] :movie/emotions)
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
    (ts/merge-schema conn {:owner (merge schema/ref
                                         schema/unique-id
                                         schema/one)
                           :person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)})
    (ts/transact conn [{:person/name "Fred"}])
    (ts/transact conn [{:owner [:person/name "Fred"]
                        :person/name "Ball"}]))

  (let [[dm-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (db/with-conn dm-conn expr)
         (db/with-conn dl-conn expr)
         (db/with-conn mem-conn expr))

      (->> [:movie/title "Repo Man"]
           (db/pull '[*
                      (:movie/title :db/id true)
                      {:movie/top-emotion [(:emotion/name :db/id true)]}
                      {:movie/emotions [(:emotion/name :db/id true)]}])
           (#(update % :movie/emotions set)))

      (into #{} (map :person/name) (:_owner (db/entity [:person/name "Fred"])))
      (into #{} (map :person/name) (:_owner (db/pull '[:person/name :_owner] [:person/name "Fred"])))

      (->> [:movie/title "Mr. Bean"]
           (db/pull '[:movie/emotions]))))

  (let [[dm-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (db/with-conn dm-conn expr)
         (db/with-conn dl-conn expr)
         (db/with-conn mem-conn expr))

      (->> [:movie/title "The Goonies"]
           (db/pull '[*])
           :movie/emotions
           (every? map?))

      (->> [:movie/title "The Goonies"]
           (db/pull '[*])
           :movie/emotions
           (map :movie/title))

      )))

(defn gen-movies [n]
  (let [mov-ns (range n)]
    (for [i mov-ns]
      {:movie/title (str "mov-" i)
       :movie/emotions (mapv (fn [ii] {:emotion/name (str "emo-" (* ii i))})
                             (range (rem i 10)))})))

(deftest pull
  (r/session
   (db/with-conn {:person/name schema/unique-id}
     (db/transact! [{:person/name "Foo"}])
     (is (= (db/pull '[*] [:person/name "Foo"])
            {:person/name "Foo"}))
     (let [rx (r/reaction! (db/pull '[*] [:person/name "Foo"]))]
       (is (= @rx {:person/name "Foo"}))
       (db/transact! [[:db/add [:person/name "Foo"] :age 42]])
       (is (= @rx {:person/name "Foo" :age 42}))))))

(deftest transform-with-tx
  (db/with-conn {:movie/title schema/unique-id
                 :emotion/name schema/unique-id
                 :movie/emotions (merge schema/ref
                                        schema/many)}
    (let [!compute-count (atom 0)]
      (r/session
       (let [!rx (r/reaction {:xf (map identity)}
                   (swap! !compute-count inc)
                   (->> (db/where [:movie/title])
                        (filter (comp #{3} count :movie/emotions))
                        (sort-by :movie/title)
                        (map (db/pull [:movie/title {:movie/emotions [:emotion/name]}]))
                        (into [])))]
         (is (= @!compute-count 0))
         (is (= [] @!rx))
         (is (= @!compute-count 1))
         (db/transact! (gen-movies 100))
         (is (= @!compute-count 2))
         (is (not= [] @!rx))
         @!rx)))))

(deftest hook-errors
  (let [!n (r/atom 0)
        !rx (r/reaction!
             (if (even? @!n)
               @(hooks/use-state :state)
               @(hooks/use-ref :ref)))]
    (is (= @!rx :state))
    (is (thrown? Exception (do (swap! !n inc) @!rx))
        "rx should error if hooks are called inconsistently")))

(deftest attribute-resolvers
  (binding [read/*attribute-resolvers* {:movie/title-lowercase
                                        (fn [{:keys [movie/title]}]
                                          (str/lower-case title))}]
    (doseq [conn (map :conn databases)]
      (db/with-conn conn
        (is (= "the goonies"
               (->> [:movie/title "The Goonies"]
                    (db/pull [:movie/title-lowercase])
                    :movie/title-lowercase)))))))



(comment

 ;; pull api
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull [:movie/title
                 {:movie/emotions [:emotion/name]}
                 {:movie/top-emotion [:emotion/name]}])))

 ;; aliases
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[(:movie/title :as :title)
                  {(:movie/emotions :as :emos) [(:emotion/name :as :person/name)]}])))

 ;; :id option - use as lookup ref
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[(:movie/title :db/id true)])))

 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[*
                  {:movie/top-emotion [*]}])))

 ;; logged lookups

 (db/with-conn (dm-db)
   (db/where [[:movie/title "Repo Man"]]))

 (db/with-conn (dm-db)
   (db/where [:movie/title]))

 (db/with-conn (dm-db)
   (read/get [:movie/title "Repo Man"] :movie/top-emotion))

 ;; filtering api
 (db/with-conn (dm-db)
   (db/where                                                ;; the first clause selects a group of entities
    [:movie/title "Repo Man"]
    ;; subsequent clauses filter the initial set of entities
    [:movie/top-emotion [:emotion/name "sad"]]))

 (db/with-conn (dm-db)
   (->> (q/where [:movie/release-year 1985])
        (mapv deref)
        #_(q/pull [:movie/release-year :movie/title]))
   (read/captured-patterns))

 '(db/pull '[* {:sub/child [:person/name :birthdate]}])

 (let [a (r/atom 0)
       my-query (-> (db/bound-reaction dm-conn (doto (* @a 10) prn))
                    r/compute!)]
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
 (ts/merge-schema dm-conn {:person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)
                           :pets (merge schema/ref
                                        schema/many)})
 (ts/transact dm-conn [{:db/id -1
                        :person/name "Mr. Porcupine"
                        :_pets {:db/id -2
                                :person/name "Sally"
                                :hair-color "brown"}}])
 )

(deftest ident-refs
  (is
   (apply =
          (for [{:keys [id conn]} databases]
            (db/with-conn conn
              (db/merge-schema! {:favorite-attribute (merge schema/ref
                                                            schema/one)
                                 :person/name {}
                                 :id (merge schema/unique-id
                                            schema/string
                                            schema/one)})
              (db/transact! [{:db/ident :person/name}
                             {:id "A"
                              :favorite-attribute :person/name}])
              [(-> (db/entity [:id "A"]) :favorite-attribute)
               (->> [:id "A"] (db/pull '[:favorite-attribute]))
               (->> [:id "A"] (db/pull '[*]))])))))

(deftest idents
  (db/with-conn dm-conn

    (is
     (apply =
            (for [{:keys [conn id]} databases
                  :when (not= id :datalevin)]
              (db/with-conn conn
                (db/merge-schema! {:service/commission (merge schema/ref schema/one)
                                   :system/id (merge schema/unique-id schema/one schema/string)
                                   :commission/name (merge schema/string schema/one)
                                   :service/phase (merge schema/ref schema/one)
                                   :phase/entry {}})
                (db/transact! [{:db/id "1"
                                :system/id "S"
                                :service/phase :phase/entry
                                :service/commission "2"}
                               {:db/id "2"
                                :system/id "C"
                                :commission/name "A commission"}])
                [(some? (first (db/where [[:service/phase :phase/entry]
                                          [:service/commission [:system/id "C"]]])))
                 (some? (first (db/where [[:service/commission [:system/id "C"]]
                                          [:service/phase :phase/entry]])))
                 (db/pull '[:service/phase] [:system/id "S"])]))))))

;; issues
;; - support for keywords-as-refs in re-db (? what does this even mean)
;; - support for resolving idents

(comment

 (let [movies (gen-movies 1000)]
   (def ds-conn (ds/create-conn {:movie/title (merge schema/unique-id)
                                 :movie/emotions (merge schema/ref
                                                        schema/many)}))
   (ds/transact! ds-conn movies)
   (transact! movies)
   nil)

 (tufte/add-basic-println-handler! {})



 (first (ds/q '[:find ?title
                :where [?m :movie/title ?title]]
              @ds-conn))

 (profile {}
   ;; DataScript Pull: 65 ms
   (time
    (p :datascript
       (dotimes [_ 10000]
         (ds/pull @ds-conn
                  '[:movie/title
                    {:movie/emotions [:emotion/name]}] [:movie/title "mov-991"]))))

   ;; Re-DB Pull: 52 ms
   (time
    (p :re-db
       (dotimes [_ 10000]
         (read/pull mem-conn
                    '[:movie/title
                      {:movie/emotions [:emotion/name]}] [:movie/title "mov-991"]))))

   ;; Datomic Pull: 79 ms
   (time
    (p :datomic
       (dotimes [_ 10000]
         (dm/pull (dm/db dm-conn)
                  '[:movie/title
                    {:movie/emotions [:emotion/name]}]
                  [:movie/title "mov-991"]))))

   ;; Datomic Pull via Re-DB: 247 ms
   (time
    (p :datomic-re-db
       (dotimes [_ 10000]
         (read/pull dm-conn
                    '[:movie/title
                      {:movie/emotions [:emotion/name]}]
                    [:movie/title "mov-991"])))))

 ;; QUESTIONS

 ;; Datomic: use entid or aveet index?
 (let [db (dm/db dm-conn)]
   ;; RESULT: no significant difference between using entid vs (datoms .. :avet)
   (time
    (dotimes [_ 100000]
      (dm/entid db [:movie/title "mov-990"])))
   (time
    (dotimes [_ 100000]
      (first (map :e (dm/datoms db :avet :movie/title "mov-990"))))))

 ;; Datomic: use d/attribute or d/entity?
 (let [db (dm/db dm-conn)]
   ;; RESULT: d/attribute is 50% faster
   (time
    (dotimes [_ 100000]
      (let [schema (dm/entity db :movie/emotions)]
        [(:db/index schema)
         (= :db.type/ref (:db/valueType schema))
         (= :db.cardinality/many (:db/cardinality schema))])))
   (time
    (dotimes [_ 100000]
      (let [schema (dm/attribute db :movie/emotions)]
        [(:indexed schema)
         (= :db.type/ref (:value-type schema))
         (= :db.cardinality/many (:cardinality schema))])))
   )

 )