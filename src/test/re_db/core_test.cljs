(ns re-db.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-db.core :as d :include-macros true]
            [re-db.read :as read]
            [re-db.api :as api]
            [re-db.schema :as schema]
            [reagent.core :as r])
  (:require-macros [re-db.test-helpers :refer [throws]]))

(deftest datom-tx

  (let [schema {:authors {:db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/many}
                :pet {:db/valueType :db.type/ref}}
        tx [{:db/id "fred"
             :name "Fred"
             :pet {:db/id "fido" :name "Fido"}}
            {:db/id "mary"
             :name "Mary"}
            {:db/id "1" :authors #{"fred" "mary"}}
            ;[:db/add "1" :authors #{"fred" "mary"}]
            [:db/add "1" :name "One"]]
        conn (d/create-conn schema)
        tx-report (d/transact! conn tx)]

    (is (= @conn
           @(doto (d/create-conn schema)
              (d/transact! [[:db/datoms (:datoms tx-report)]])))
        :db/datoms)

    (is (= @(d/create-conn schema)
           (let [conn (d/create-conn schema)
                 {:keys [datoms]} (d/transact! conn tx)]
             (d/transact! conn [[:db/datoms-reverse datoms]])
             @conn)
           @(doto (d/create-conn schema)
              (d/transact! [[:db/datoms (:datoms tx-report)]
                            [:db/datoms-reverse (:datoms tx-report)]])))
        :db/datoms-reverse)

    ;(read/touch db (read/get db "fred"))
    (is (= {:db/id "fred"
            :name "Fred"
            :_authors #{"1"}
            :pet "fido"} (read/touch (read/entity conn "fred")))
        "refs with cardinality-many")))

(deftest upserts
  (let [db (doto (d/create-conn {:email {:db/unique :db.unique/identity}
                                 :friend {:db/valueType :db.type/ref}
                                 :pets {:db/valueType :db.type/ref
                                        :db/cardinality :db.cardinality/many}
                                 :pet/collar-nr {:db/unique :db.unique/identity}})
             (d/transact! [{:db/id "fred"
                            :email "fred@example.com"
                            :friend {:email "matt@example.com"}
                            :pets #{{:pet/collar-nr 1}
                                    {:pet/collar-nr 2}}}
                           {:email "matt@example.com"}
                           {:email "matt@example.com"
                            :name "Matt"}
                           {:pet/collar-nr 1
                            :pet/name "pookie"}
                           {:pet/collar-nr 2
                            :pet/name "fido"}]))]
    (is (= (-> db
               (read/entity "fred")
               :friend
               :db/id)
           (-> db
               (read/entity [:email "matt@example.com"])
               :db/id)))
    (is (= "Matt"
           (-> db
               (read/entity "fred")
               :friend
               :name)))

    (is (= #{"pookie" "fido"}
           (->> (read/entity db "fred")
                :pets
                (map :pet/name)
                set)))

    ))

(deftest lookup-refs
  (let [db (doto (d/create-conn {:email {:db/unique :db.unique/identity}
                                 :friend {:db/valueType :db.type/ref}})
             (d/transact! [{:db/id "fred"
                            :email "fred@example.com"
                            :friend [:email "matt@example.com"]}
                           {:email "matt@example.com"
                            :friend [:email "fred@example.com"]}
                           {:db/id "herman"
                            :friend {:email "peter@example.com"}}]))]
    (is (= (read/get db "fred")
           (read/get db [:email "fred@example.com"]))
        "Can substitute unique attr for id (à la 'lookup refs')")
    (is (= "matt@example.com"
           (-> (read/entity db "fred")
               :friend
               :email))
        "Lookup ref as ref")
    (is (= (-> (read/entity db "fred")
               :friend
               :db/id)
           (-> (read/entity db [:email "matt@example.com"])
               :db/id))
        "transact a map with no id, only a unique attribute")
    (is (= "peter@example.com"
           (-> db (read/entity "herman") :friend :email)))
    (is (= "fred"
           (-> db
               (read/entity [:email "matt@example.com"])
               :friend
               :db/id)))))

(deftest basic
  (let [conn (d/create-conn {:dog {:db/index true}})
        tx-log (atom [])]

    (is (satisfies? cljs.core/IDeref conn)
        "DB is an atom")

    (d/listen! conn ::basic #(swap! tx-log conj (:datoms %2)))

    (d/transact! conn [{:db/id "herman"}])

    ;; allow empty entities - can be used as relations
    #_(is (false? (read/contains? conn "herman"))
          "Inserting an entity without attributes is no-op")

    (d/transact! conn [{:db/id "herman" :occupation "teacher"}])

    (is (= (js->clj (last @tx-log))
           [["herman" :occupation "teacher" nil]])
        "Tx-log listener called with datoms")

    (is (= {:db/id "herman" :occupation "teacher"} (read/get conn "herman"))
        "Entity is returned as it was inserted")

    (is (= "herman" (:db/id (read/get conn "herman")))
        "Entity is returned with :db/id attribute")

    (is (= "teacher" (read/get conn "herman" :occupation))
        "read/get an attribute of an entity")

    (is (= 1 (count (read/where conn [[:occupation "teacher"]])))
        "Query on non-indexed attr")

    (d/transact! conn [{:db/id "fred" :occupation "teacher"}])

    (is (= 2 (count (read/where conn [[:occupation "teacher"]])))
        "Verify d/insert! and query on non-indexed field")

    (d/transact! conn [[:db/retract "herman" :occupation]])

    (is (nil? (read/get conn "herman" :occupation))
        "Retract attribute")

    (is (= [["herman" :occupation nil "teacher"]]
           (js->clj (last @tx-log)))
        "retraction datom")

    (is (= 1 (count (read/where conn [[:occupation "teacher"]])))
        "Retract non-indexed field")

    (d/transact! conn [[:db/retract "herman" :db/id]])
    (is (nil? (read/get conn "herman"))
        "Entity with no attributes is removed")

    (is (false? (contains? (get-in conn [:ave :db/id]) "herman"))
        "Index has been removed")

    (d/transact! conn [{:db/id "me"
                        :dog "herman"
                        :name "Matt"}])
    (d/transact! conn [{:db/id "me"
                        :dog nil}])

    (is (empty? (read/ids-where conn [[:dog "herman"]]))
        "Setting a value to nil is equivalent to retracting it")

    #_(is (= :error (try (d/transact! conn [[:db/add "fred" :db/id "some-other-id"]])
                         nil
                         (catch js/Error e :error)))
          "Cannot change :db/id of entity")

    (testing "map txs are merged"
      (api/with-conn {}
        (api/transact! [{:db/id "a"
                         :name/first "b"
                         :name/last "c"}])
        (api/transact! [{:db/id "a"
                         :name/first "b1"}])
        (is (= (api/get "a" :name/last) "c"))))))




(deftest refs
  (let [db (doto (d/create-conn {:owner (merge schema/ref
                                               schema/unique-value)})
             (d/transact! [{:db/id "fred"
                            :name "Fred"}
                           {:db/id "ball"
                            :name "Ball"
                            :owner "fred"}]))]
    (is (= {:db/id "fred"
            :name "Fred"
            :_owner #{"ball"}}
           (-> (read/entity db "fred")
               (read/touch)))
        "touch adds refs to entity"))

  (let [conn (doto (d/create-conn {:authors {:db/valueType :db.type/ref
                                           :db/cardinality :db.cardinality/many}
                                 :pet {:db/valueType :db.type/ref}})
             (d/transact! [{:db/id "fred"
                            :name "Fred"
                            :pet {:db/id "fido"}}
                           {:db/id "mary"
                            :name "Mary"}
                           {:db/id "1"
                            :name "One"
                            :authors #{"fred" "mary"}}
                           #_[:db/add "1" :authors #{"fred" "mary"}]]))]
    (is (= {:db/id "fred"
            :name "Fred"
            :_authors #{"1"}
            :pet "fido"} (read/touch (read/entity conn "fred")))
        "refs with cardinality-many")
    (is (= {:db/id "fido"}
           (read/get conn "fido")))))

(deftest touch-refs

  (api/with-conn {:children (merge schema/ref
                                   schema/many)}
    (api/transact! [{:db/id "A"
                     :children #{"A.0"}}
                    {:db/id "A.0"
                     :children #{"A.1" #_"A"}}
                    {:db/id "A.1"
                     :children #{"A.2"}}
                    {:db/id "A.2"
                     :children #{"A.3"}}
                    {:db/id "A.3"
                     :children #{"A.4"}}])

    (is (-> (api/pull "A" [:children])
            :children first :children
            (= #{"A.1"})))

    (is (-> (api/pull "A" [{:children 1}])
            :children first :children first :children
            (= #{"A.2"})))

    (is (-> (api/pull "A" [{:children :...}])
            :children first :children first :children first :children first :children
            (= #{"A.4"}))))

  (api/with-conn {:child schema/ref}
    (api/transact! [{:db/id "A"
                     :name "A"
                     :child {:db/id "B"
                             :name "B"}}])
    (is (-> (api/pull "A" [:child])
            (= {:db/id "A"
                :name "A"
                :child {:db/id "B"
                        :name "B"}})))

    (is (-> (api/entity "A")
            (api/touch)
            :child
            type
            (= js/String)))

    (is (-> (api/entity "A")
            (api/touch)
            :child
            (= "B")))))

(deftest custom-db-operations
  ;; add an operation by
  (api/with-conn {:db/tx-fns {:db/times-ten (fn [db [_ e a v]]
                                                  [[:db/add e a (* 10 v)]])}}
    (api/transact! [[:db/times-ten :matt :age 3.9]])
    (is (= 39 (api/get :matt :age))
        "Custom db/operation can be specified in schema"))

  (api/with-conn {:db/tx-fns {:db/times (fn [db [_ e a v]]
                                              [[:db/add e a (* (:multiplier (d/get-entity db :times) 0) v)]])}}
    (api/transact! [[:db/add :times :multiplier 10]
                    [:db/times :matt :age 3.9]])
    (is (= 39 (api/get :matt :age))
        "custom db/operation can read from db")))

#_(comment
   ;; idea: schemaless "touch" - pass in pull syntax to crawl relationships

   ;; crawl "children
   (d/touch e [:children])
   ;; crawl children-of-children up to 4 levels deep
   (d/touch e [{:children 4}])
   ;; crawl children-of-children infinitely deep
   (d/touch e [{:children :...}])
   )

(deftest cardinality-many
  (let [conn (doto (d/create-conn {:children (merge schema/many
                                                    schema/ave)})
               (d/transact! [{:db/id "fred"
                              :children #{"pete"}}]))]

    (is (true? (contains? (get-in @conn [:ave :children]) "pete"))
        "cardinality/many attribute can be indexed")

    ;; second child
    (d/transact! conn [[:db/add "fred" :children #{"sally"}]])


    (is (= #{"sally" "pete"} (read/get conn "fred" :children))
        "cardinality/many attribute returned as set")

    (is (= #{"fred"}
           (read/ids-where conn [[:children "sally"]])
           (read/ids-where conn [[:children "pete"]]))
        "look up via cardinality/many index")


    (testing "remove value from cardinality/many attribute"
      (d/transact! conn [[:db/retract "fred" :children #{"sally"}]])
      (is (= nil (read/ids-where conn [[:children "sally"]]))
          "index is removed on retraction")
      (is (= #{"fred"} (read/ids-where conn [[:children "pete"]]))
          "index remains for other value")
      (is (= #{"pete"} (read/get conn "fred" :children))
          "attribute has correct value"))

    (d/transact! conn [{:db/id "fred" :children #{"fido"}}])
    (is (= #{"fido"} (read/get conn "fred" :children))
        "Map transaction replaces entire set")

    (testing "unique attrs, duplicates"

      (api/with-conn {:ssn schema/unique-id
                      :email schema/unique-id
                      :pets (merge schema/many
                                   schema/unique-value)}

        ;; cardinality single
        (api/transact! [[:db/add "fred" :ssn "123"]])
        (is (= "fred" (:db/id (api/get [:ssn "123"]))))
        (throws (api/transact! [[:db/add "herman" :ssn "123"]])
                "Cannot have two entities with the same unique attr")

        ;; cardinality many
        (api/transact! [[:db/add "fred" :pets #{"fido"}]])
        (is (= "fred" (:db/id (api/get [:pets "fido"]))))
        (throws (api/transact! [[:db/add "herman" :pets #{"fido"}]])
                "Two entities with same unique :db.cardinality/many attr")
        (throws (api/transact! [{:db/id "herman"
                                 :pets #{"fido"}}])
                "Two entities with same unique :db.cardinality/many attr")))))

(deftest permissions
  (let [conn (doto (d/create-conn {:permission/person {:db/valueType :db.type/ref}
                                   :group/profile {:db/valueType :db.type/ref}
                                   :permission/group {:db/valueType :db.type/ref}})
               (d/transact! [{:db/ident :person/current
                              :person/name "St. Leonhards"}
                             {:db/id "group1"
                              :group/profile "profile1"}
                             {:db/id "profile1"
                              :profile/name "a :profile/name"}
                             {:db/id "permission1"
                              :permission/person [:db/ident :person/current]
                              :permission/group "group1"
                              :permission/ability :permission.ability/manage}]))]

    (->> (read/entity conn [:db/ident :person/current])
         :permission/_person
         (filter (comp #{:permission.ability/manage
                         :permission.ability/edit}
                       :permission/ability))
         (map (comp :profile/name :group/profile :permission/group))
         )
    )
  )

(deftest where

  (let [conn (read/create-conn {:person/id (merge
                                            schema/unique-id
                                            schema/ae)
                                :pet/id schema/unique-id
                                :person/pets (merge schema/many
                                                    schema/ref)})]
    (d/transact! conn [{:db/id 1
                        :person/id 1
                        :name "1"
                        :pets #{[:pet/id 1.1]}}
                       {:db/id 1.1
                        :pet/id 1.1 :name "1.1"}
                       {:db/id 2
                        :person/id 2 :name "2"}
                       {:db/id 2.1
                        :pet/id 2.1 :name "2.1"}])

    (is (= #{1 2} (read/ids-where conn [:person/id])))
    (is (= #{1.1 2.1} (read/ids-where conn [:pet/id])))
    (is (= #{1 2} (get-in @conn [:ae :person/id])))
    ))

(deftest equality
  (let [conn (read/create-conn {:email schema/unique-id})]
    (d/transact! conn [{:db/id "a"
                        :name "b"
                        :email "c"}])
    (let [e1 (read/entity conn "a")
          e2 (read/entity conn "a")]
      (is (identical? @e1 @e2))
      (is (= e1 e2))
      (is (= (hash e1) (hash e2))))

    (let [e1 (read/entity conn "a")
          e2 (read/entity conn [:email "c"])]
      (is (= @e1 @e2))
      (is (= e1 e2))
      (is (= (hash e1) (hash e2))))))

(deftest meta-impl
  (api/with-conn {}
    (let [entity (api/entity 0)
          m {:a 1}]

      (is (= m (meta (with-meta entity m))))

      (is (= {} (-> entity
                    (with-meta m)
                    (vary-meta dissoc :a)
                    meta))))))