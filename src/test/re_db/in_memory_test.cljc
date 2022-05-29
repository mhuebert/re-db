(ns re-db.in-memory-test
  (:require #?(:clj [datomic.api :as dm])
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [re-db.in-memory :as mem]
            [re-db.api :as d]
            [re-db.read :as read :refer [entity #?(:cljs Entity) pull]]
            [re-db.schema :as schema]
            [re-db.util :as util]
            [re-db.test-helpers :refer [throws]]
            [re-db.in-memory :as db])
  #?(:clj (:import [re_db.read Entity])))

(defn ids [entities] (util/guard (into #{} (map :db/id) entities)
                                 seq))

(defn db= [& dbs]
  (apply = (map #(dissoc % :tx) dbs)))

(def pets-schema {:person/pet (merge schema/ref
                                     schema/one)
                  :pet/name (merge schema/unique-id
                                   schema/one
                                   {:db/valueType :db.type/string})
                  :person/name (merge
                                schema/one
                                schema/unique-id
                                {:db/valueType :db.type/string})
                  :person/friends (merge schema/ref
                                         schema/many)})
(def pets-tx [{:db/id "mary"
               :person/name "Mary"
               :person/pet [:pet/name "safran"] ;; upsert via lookup ref
               :person/friends #{"fred"
                                 "sally"
                                 "william"}}
              {:db/id "fred"
               :person/name "Fred"
               :person/pet {:db/id "fido" :pet/name "Fido"} ;; upsert via map
               :person/friends #{"mary"}}

              [:db/add "billy" :pet/name "Billy"]
              [:db/add "whisper" :pet/name "Whisper"]

              {:db/id "william"
               :person/name "William"
               :person/pets #{"billy"}}
              {:db/id "sally"
               :person/name "Sally"
               :person/pets #{"whisper"}}])


(deftest datom-tx
  (let [conn (mem/create-conn pets-schema)
        tx-report (mem/transact! conn pets-tx)]
    (d/with-conn conn
      (is (= (dissoc @conn :schema :tx)
             (dissoc @(doto (mem/create-conn pets-schema)
                        (mem/transact! [[:db/datoms (:datoms tx-report)]])) :schema :tx))
          :db/datoms)

      (is (= (dissoc @(mem/create-conn pets-schema) :tx :schema)
             (dissoc (let [conn (mem/create-conn pets-schema)
                           {:keys [datoms]} (mem/transact! conn pets-tx)]
                       (mem/transact! conn [[:db/datoms-reverse datoms]])
                       @conn) :tx :schema)
             (dissoc @(doto (mem/create-conn pets-schema)
                        (mem/transact! [[:db/datoms (:datoms tx-report)]
                                        [:db/datoms-reverse (:datoms tx-report)]])) :tx :schema))
          :db/datoms-reverse)

      #_(is (= {:db/id "fred"
                :name "Fred"
                :_pets #{"1"}
                :pet "fido"} (-> (pull '[* :_pets] "fred")
                                 (update :_pets ids)))
            "refs with cardinality-many"))))

(deftest upserts
  (let [schema {:email (merge schema/unique-id
                              schema/one
                              schema/string)
                :friend (merge schema/ref
                               schema/one)
                :pets (merge schema/ref
                             schema/many)
                :pet/collar-nr (merge schema/one
                                      schema/unique-id
                                      {:db.type/valueType :db.type/bigint})}]
    (d/with-conn (doto (mem/create-conn schema)
                   (mem/transact! [{:db/id "fred"
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
                                    :pet/name "fido"}
                                   {:db/id [:email "peter@example.com"]
                                    :name "Peter"}
                                   {:email "peter@example.com"
                                    :age 32}]))
      (is (= {:email "matt@example.com"}
             (pull [:email] [:email "matt@example.com"])))
      (is (= (-> (entity "fred")
                 :friend
                 :db/id)
             (-> (entity [:email "matt@example.com"])
                 :db/id)))
      (is (= "Matt"
             (-> (entity "fred")
                 :friend
                 :name)))

      (is (= #{"pookie" "fido"}
             (->> (entity "fred")
                  :pets
                  (map :pet/name)
                  set)))

      (is (= ["Peter" 32]
             (->> (entity [:email "peter@example.com"])
                  ((juxt :name :age)))))

      )))

(defn ->clj [x] #?(:cljs (js->clj x) :clj x))

(deftest tempids
  (d/with-conn (doto (mem/create-conn {:email schema/unique-id
                                       :friends (merge schema/ref
                                                       schema/many)})
                 (mem/transact! [{:db/id -1
                                  :email "fred@x.com"}
                                 {:db/id -2
                                  :email "matt@x.com"
                                  :friends [-1]}
                                 {:db/id -3
                                  :email "herman@x.com"
                                  :friends [-1]}]))
    (is (= (d/entity [:email "fred@x.com"])
           (first (:friends (d/entity [:email "matt@x.com"])))))
    (is (= #{(d/entity [:email "matt@x.com"])
             (d/entity [:email "herman@x.com"])}
           (set (:_friends (d/entity [:email "fred@x.com"])))))
    (is (not= -1 (:db/id (d/entity [:email "fred@x.com"]))))))

(deftest lookup-refs
  (d/with-conn (doto (mem/create-conn {:email {:db/unique :db.unique/identity}
                                       :friend {:db/valueType :db.type/ref}})
                 (mem/transact! [{:db/id "fred"
                                  :email "fred@example.com"
                                  :friend [:email "matt@example.com"]}
                                 {:email "matt@example.com"
                                  :friend [:email "fred@example.com"]}
                                 {:db/id "herman"
                                  :friend {:email "peter@example.com"}}]))
    (is (= (d/get "fred")
           (d/get [:email "fred@example.com"]))
        "Can substitute unique attr for id (à la 'lookup refs')")
    (is (= "matt@example.com"
           (-> (entity "fred")
               :friend
               :email))
        "Lookup ref as ref")
    (is (= (-> (entity "fred")
               :friend
               :db/id)
           (-> (entity [:email "matt@example.com"])
               :db/id))
        "transact a map with no id, only a unique attribute")
    (is (= "peter@example.com"
           (-> (entity "herman") :friend :email)))

    (is (= "fred"
           (-> (entity [:email "matt@example.com"])
               :friend
               :db/id)))))


(deftest basic

  (d/with-conn {:dog {:db/index true}}
    (let [tx-log (atom [])
          conn (d/conn)]

      (is (map? @conn) "DB is an atom")

      (mem/listen! conn ::basic #(swap! tx-log conj (:datoms %2)))

      (mem/transact! conn [{:db/id "herman"}])

      (mem/transact! conn [{:db/id "herman" :occupation "teacher"}])

      (is (= (->clj (last @tx-log))
             [["herman" :occupation "teacher" nil]])
          "Tx-log listener called with datoms")

      (is (= {:db/id "herman" :occupation "teacher"} (d/get "herman"))
          "Entity is returned as it was inserted")

      (is (= "herman" (:db/id (d/get "herman")))
          "Entity is returned with :db/id attribute")

      (is (= "teacher" (d/get "herman" :occupation))
          "api/get an attribute of an entity")

      (is (= 1 (count (d/where [[:occupation "teacher"]])))
          "Query on non-indexed attr")

      (mem/transact! conn [{:db/id "fred" :occupation "teacher"}])

      (is (= 2 (count (d/where [[:occupation "teacher"]])))
          "Verify d/insert! and query on non-indexed field")

      (mem/transact! conn [[:db/retract "herman" :occupation]])

      (is (nil? (d/get "herman" :occupation))
          "Retract attribute")

      (is (= [["herman" :occupation nil "teacher"]]
             (->clj (last @tx-log)))
          "retraction datom")

      (is (= 1 (count (d/where [[:occupation "teacher"]])))
          "Retract non-indexed field")

      (is (nil? (d/get "herman"))
          "Entity with no attributes is removed")

      (is (false? (contains? (get-in conn [:ave :db/id]) "herman"))
          "Index has been removed")

      (mem/transact! conn [{:db/id "me"
                            :dog "herman"
                            :name "Matt"}])
      (mem/transact! conn [{:db/id "me"
                            :dog nil}])

      (is (empty? (d/where [[:dog "herman"]]))
          "Setting a value to nil is equivalent to retracting it")

      (is (not (contains? @(entity conn "me") :dog))
          "Nil in a map removes ")

      #_(is (= :error (try (mem/transact! conn [[:db/add "fred" :db/id "some-other-id"]])
                           nil
                           (catch js/Error e :error)))
            "Cannot change :db/id of entity")

      (testing "map txs are merged"
        (d/with-conn {}
          (d/transact! [{:db/id "a"
                         :name/first "b"
                         :name/last "c"}])
          (d/transact! [{:db/id "a"
                         :name/first "b1"}])
          (is (= (d/get "a" :name/last) "c")))))))


(deftest refs
  (let [schema {:owner (merge schema/ref
                              schema/unique-value)}]
    (d/with-conn (doto (mem/create-conn schema)
                   (mem/transact! [{:db/id "fred"
                                    :name "Fred"}
                                   {:db/id "ball"
                                    :name "Ball"
                                    :owner "fred"}]))
      (is (= {:db/id "fred"
              :name "Fred"
              :_owner [{:db/id "ball"}]}
             (pull '[* :_owner] "fred"))
          "reverse refs")))

  (d/with-conn (doto (mem/create-conn {:authors {:db/valueType :db.type/ref
                                                 :db/cardinality :db.cardinality/many}
                                       :pet {:db/valueType :db.type/ref}})
                 (mem/transact! [{:db/id "fred"
                                  :name "Fred"
                                  :pet {:db/id "fido"}}
                                 {:db/id "mary"
                                  :name "Mary"}
                                 {:db/id "1"
                                  :name "One"
                                  :authors #{"fred" "mary"}}
                                 #_[:db/add "1" :authors #{"fred" "mary"}]]))
    (is (= {:db/id "fred"
            :name "Fred"
            :_authors [{:db/id "1"}]
            :pet {:db/id "fido"}} (pull '[* :_authors] "fred"))
        "refs with cardinality-many")
    (is (= {:db/id "fido"}
           (d/get "fido")))))

(deftest touch-refs

  (d/with-conn {:children (merge schema/ref
                                 schema/many)
                :child (merge schema/ref
                              schema/one)}
    (d/transact! [{:db/id "A"
                   :children #{"A.0"}}
                  {:db/id "A.0"
                   :children #{"A.1" #_"A"}}
                  {:db/id "A.1"
                   :children #{"A.2"}}
                  {:db/id "A.2"
                   :children #{"A.3"}}
                  {:db/id "A.3"
                   :children #{"A.4"}}
                  {:db/id "B"
                   :name "B"
                   :child "B1"}
                  {:db/id "B1"
                   :name "B1"
                   :child "B2"}
                  {:db/id "B2"
                   :name "B2"}])

    (is (= (d/pull '[:db/id {:child :...}] "B")
           {:db/id "B"
            :child {:db/id "B1"
                    :child {:db/id "B2"}}}))

    (is (= [{:db/id "B1"}]
           (-> (d/pull [:_child] "B2")
               :_child)))

    (is (= {:db/id "A.0"}
           (-> (d/pull [:children] "A")
               :children first)))

    (is (= {:db/id "A.1"}
           (-> (d/pull [{:children 1}] "A")
               :children first :children first)))

    (is (= {:db/id "A.2"}
           (-> (d/pull [{:children 2}] "A")
               :children first :children first :children first)))

    (is (= [{:db/id "A.4"}]
           (-> (d/pull [:db/id {:children :...}] "A")
               :children first :children first :children first :children first :children)))))

(deftest custom-db-operations
  ;; add an operation by
  (d/with-conn {:db/tx-fns {:db/times-ten (fn [db [_ e a v]]
                                            [[:db/add e a (* 10 v)]])}}
    (d/transact! [[:db/times-ten :matt :age 3.9]])
    (is (= 39.0 (d/get :matt :age))
        "Custom db/operation can be specified in schema"))

  (d/with-conn {:db/tx-fns {:db/times (fn [db [_ e a v]]
                                        [[:db/add e a (* (:multiplier (mem/get-entity db :times) 0) v)]])}}
    (d/transact! [[:db/add :times :multiplier 10]
                  [:db/times :matt :age 3.9]])
    (is (= 39.0 (d/get :matt :age))
        "custom db/operation can read from db")))

#_(comment
   ;; idea: schemaless "touch" - pass in pull syntax to crawl relationships

   ;; crawl "children
   (mem/touch e [:children])
   ;; crawl children-of-children up to 4 levels deep
   (mem/touch e [{:children 4}])
   ;; crawl children-of-children infinitely deep
   (mem/touch e [{:children :...}])
   )

(deftest cardinality-many
  (d/with-conn (doto (mem/create-conn {:children (merge schema/many
                                                        schema/ave)})
                 (mem/transact! [{:db/id "fred"
                                  :children #{"pete"}}]))
    (let [conn (d/conn)]

      (is (true? (contains? (get-in @conn [:ave :children]) "pete"))
          "cardinality/many attribute can be indexed")

      ;; second child
      (d/transact! [[:db/add "fred" :children "sally"]])


      (is (= #{"sally" "pete"} (d/get "fred" :children))
          "cardinality/many attribute returned as set")
      (is (= #{"fred"}
             (ids (d/where [[:children "sally"]]))
             (ids (d/where [[:children "pete"]])))
          "look up via cardinality/many index")


      (testing "remove value from cardinality/many attribute"
        (d/transact! [[:db/retract "fred" :children "sally"]])
        (is (= nil (ids (d/where [[:children "sally"]])))
            "index is removed on retraction")
        (is (= #{"fred"} (ids (d/where [[:children "pete"]])))
            "index remains for other value")
        (is (= #{"pete"} (d/get "fred" :children))
            "attribute has correct value"))

      (d/transact! [{:db/id "fred" :children #{"fido"}}])
      (is (= #{"fido"} (d/get "fred" :children))
          "Map transaction replaces entire set")

      (testing "unique attrs, duplicates"

        (d/with-conn {:ssn schema/unique-id
                      :email schema/unique-id
                      :pets (merge schema/many
                                   schema/unique-value)}

          ;; cardinality single
          (d/transact! [[:db/add "fred" :ssn "123"]])
          (is (= "fred" (:db/id (d/get [:ssn "123"]))))
          (throws (d/transact! [[:db/add "herman" :ssn "123"]])
                  "Cannot have two entities with the same unique attr")

          ;; cardinality many
          (d/transact! [[:db/add "fred" :pets "fido"]])
          (is (= "fred" (:db/id (d/get [:pets "fido"]))))
          (throws (d/transact! [[:db/add "herman" :pets "fido"]])
                  "Two entities with same unique :db.cardinality/many attr")
          (throws (d/transact! [{:db/id "herman"
                                 :pets #{"fido"}}])
                  "Two entities with same unique :db.cardinality/many attr"))))))

(deftest permissions
  (let [conn (doto (mem/create-conn {:permission/person {:db/valueType :db.type/ref}
                                     :group/profile {:db/valueType :db.type/ref}
                                     :permission/group {:db/valueType :db.type/ref}})
               (mem/transact! [{:db/ident :person/current
                                :person/name "St. Leonhards"}
                               {:db/id "group1"
                                :group/profile "profile1"}
                               {:db/id "profile1"
                                :profile/name "a :profile/name"}
                               {:db/id "permission1"
                                :permission/person [:db/ident :person/current]
                                :permission/group "group1"
                                :permission/ability :permission.ability/manage}]))]

    (->> (entity conn [:db/ident :person/current])
         :permission/_person
         (filter (comp #{:permission.ability/manage
                         :permission.ability/edit}
                       :permission/ability))
         (map (comp :profile/name :group/profile :permission/group))
         )
    )
  )



(deftest where-queries
  (d/with-conn {:person/id (merge
                            schema/unique-id
                            schema/ae)
                :pet/id schema/unique-id
                :person/pets (merge schema/many
                                    schema/ref)}
    (d/transact! [{:db/id 1
                   :person/id 1
                   :name "1"
                   :pets #{[:pet/id 1.1]}}
                  {:db/id 1.1
                   :pet/id 1.1 :name "1.1"}
                  {:db/id 2
                   :person/id 2 :name "2"}
                  {:db/id 2.1
                   :pet/id 2.1 :name "2.1"}])

    (is (= #{1 2} (ids (d/where [:person/id]))))
    (is (= #{1.1 2.1} (ids (d/where [:pet/id]))))
    (is (= #{1 2} (get-in @(d/conn) [:ae :person/id])))

    ))

(deftest equality
  (d/with-conn {:email schema/unique-id}
    (d/transact! [{:db/id "a"
                   :name "b"
                   :email "c"}])
    (let [e1 (entity "a")
          e2 (entity "a")]
      (is (not (identical? @e1 @e2)))
      (is (= e1 e2))
      (is (= (hash e1) (hash e2))))

    (let [e1 (entity "a")
          e2 (entity [:email "c"])]
      (is (= @e1 @e2))
      (is (= e1 e2))
      (is (= (hash e1) (hash e2))))))

(deftest meta-impl
  (d/with-conn {}
    (let [entity (d/entity 0)
          m {:a 1}]

      (is (= m (meta (with-meta entity m))))

      (is (= {} (-> entity
                    (with-meta m)
                    (vary-meta dissoc :a)
                    meta))))))

(deftest reverse-lookups
  (d/with-conn {:pet schema/ref
                :tag-id schema/unique-id}
    (d/transact! [{:db/id "owner"
                   :pet [:tag-id "f1"]}
                  {:tag-id "f1"}])

    (is (some? (-> (d/entity [:tag-id "f1"])
                   :_pet
                   first
                   deref))))

  (d/with-conn {:system/id schema/unique-id
                :system/notifications {:db/valueType :db.type/ref
                                       :db/cardinality :db.cardinality/many}}
    (let [s (util/random-uuid)
          n (util/random-uuid)]
      (d/transact! [{:system/id s :system/notifications #{[:system/id n]}}])
      (d/transact! [{:notification/kind :sms :system/id n}])
      (is (= 1 (count (:system/notifications (d/entity [:system/id s]))))))))

(deftest merge-schema
  (let [conn (db/create-conn)]
    (db/merge-schema! conn {:my/unique schema/unique-id
                            :my/ref schema/ref})
    (db/transact! conn [{:my/unique 1
                         :my/ref {:my/unique 2 :my/name "Mr. Unique"}
                         :my/other {:my/unique 3}}])
    (is (= Entity
           (-> (entity conn [:my/unique 1])
               :my/ref
               type)))

    (is (= "Mr. Unique"
           (-> (entity conn [:my/unique 1])
               :my/ref
               :my/name)))

    (is (map?
         (-> (entity conn [:my/unique 1])
             :my/other)))))

(deftest add-missing-attribute
  (let [conn (db/create-conn)]
    (db/merge-schema! conn {:my/ref schema/ref})
    (is (db/ref? (db/get-schema @conn :my/ref)))
    (swap! conn db/add-missing-index :my/ref :ae)
    (is (db/ref? (db/get-schema @conn :my/ref)))
    (is (some #{{:my/ref schema/ae}}
              (-> @conn :schema :db/runtime-changes)))))