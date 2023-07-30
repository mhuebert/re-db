(ns re-db.in-memory-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [re-db.in-memory :as mem]
            [re-db.api :as db :refer [entity pull]]
            [re-db.read :as read :refer [#?(:cljs Entity)]]
            [re-db.schema :as s]
            [re-db.schema :as schema]
            [re-db.util :as util])
  #?(:clj (:import [re_db.read Entity])))


(defn throws [f]
  (try (do (f) false)
       (catch #?(:cljs js/Error :clj Exception) e true)))

(defn ids [entities] (util/guard (into #{} (map :db/id) entities)
                                 seq))

(is (defn db= [& dbs]
      (apply = (map #(dissoc % :tx) dbs))))
(is
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
                                          schema/many)}))
(def pets-tx [{:db/id "mary"
               :person/name "Mary"
               :person/pet [:pet/name "safran"]             ;; upsert via lookup ref
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
        tx-report (mem/transact! conn pets-tx)
        db-data #(dissoc % :tx :schema :tempids)]
    (db/with-conn conn
      (is (= (db-data @conn)
             (db-data @(doto (mem/create-conn pets-schema)
                         (mem/transact! [[:db/datoms (:datoms tx-report)]]))))
          :db/datoms)

      (is (= (db-data @(mem/create-conn pets-schema))
             (db-data (let [conn (mem/create-conn pets-schema)
                            {:keys [datoms]} (mem/transact! conn pets-tx)]
                        (mem/transact! conn [[:db/datoms-reverse datoms]])
                        @conn))
             (db-data @(doto (mem/create-conn pets-schema)
                         (mem/transact! [[:db/datoms (:datoms tx-report)]
                                         [:db/datoms-reverse (:datoms tx-report)]]))))
          :db/datoms-reverse)

      #_(is (= {:db/id "fred"
                :name "Fred"
                :_pets #{"1"}
                :pet "fido"} (-> (pull '[* :_pets] "fred")
                                 (update :_pets ids)))
            "refs with cardinality-many"))))
(def upsert-schema {:email (merge schema/unique-id
                                  schema/one
                                  schema/string)
                    :friend (merge schema/ref
                                   schema/one)
                    :pets (merge schema/ref
                                 schema/many)
                    :owner (merge schema/ref
                                  schema/one)
                    :pet/collar-nr (merge schema/one
                                          schema/unique-id
                                          {:db.type/valueType :db.type/bigint})})

(def upsert-tx [{:db/id "fred"
                 :email "fred@eg.com"
                 :friend {:email "matt@eg.com"}
                 :pets #{{:pet/collar-nr 1}
                         {:pet/collar-nr 2}}}
                {:email "matt@eg.com"}
                {:email "matt@eg.com"
                 :name "Matt"}
                {:pet/collar-nr 1
                 :pet/name "pookie"}
                {:pet/collar-nr 2
                 :pet/name "fido"}
                {:db/id [:email "peter@eg.com"]
                 :name "Peter"}
                {:email "peter@eg.com"
                 :age 32}
                {:email "rabbit@eg.com"
                 :owner {:db/id "fred"}                     ;; db/id map as ref
                 :name "Rabbit"}])

(deftest upserts

  (db/with-conn (doto (mem/create-conn upsert-schema)
                  (mem/transact! upsert-tx))
    (is (= {:email "fred@eg.com"}
           (->> (entity [:email "rabbit@eg.com"])
                :owner
                (db/pull [:email]))))
    (is (= {:email "matt@eg.com"}
           (db/pull [:email] [:email "matt@eg.com"])))
    (is (= (-> (entity "fred")
               :friend
               :db/id)
           (-> (entity [:email "matt@eg.com"])
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
           (->> (entity [:email "peter@eg.com"])
                ((juxt :name :age)))))

    ))

(defn ->clj [x] #?(:cljs (js->clj x) :clj x))

(deftest tempids
  (db/with-conn (doto (mem/create-conn {:email schema/unique-id
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
    (is (= (db/entity [:email "fred@x.com"])
           (first (:friends (db/entity [:email "matt@x.com"])))))
    (is (= #{(db/entity [:email "matt@x.com"])
             (db/entity [:email "herman@x.com"])}
           (set (:_friends (db/entity [:email "fred@x.com"])))))
    (is (not= -1 (:db/id (db/entity [:email "fred@x.com"]))))))

(deftest lookup-refs
  (db/with-conn (doto (mem/create-conn {:email {:db/unique :db.unique/identity}
                                        :friend {:db/valueType :db.type/ref}})
                  (mem/transact! [{:db/id "fred"
                                   :email "fred@eg.com"
                                   :friend [:email "matt@eg.com"]}
                                  {:email "matt@eg.com"
                                   :friend [:email "fred@eg.com"]}
                                  {:db/id "herman"
                                   :friend {:email "peter@eg.com"}}]))
    (is (= (db/get "fred")
           (db/get [:email "fred@eg.com"]))
        "Can substitute unique attr for id (à la 'lookup refs')")
    (is (= "matt@eg.com"
           (-> (entity "fred")
               :friend
               :email))
        "Lookup ref as ref")
    (is (= (-> (entity "fred")
               :friend
               :db/id)
           (-> (entity [:email "matt@eg.com"])
               :db/id))
        "transact a map with no id, only a unique attribute")
    (is (= "peter@eg.com"
           (-> (entity "herman") :friend :email)))

    (is (= "fred"
           (-> (entity [:email "matt@eg.com"])
               :friend
               :db/id)))))


(deftest basic

  (db/with-conn {:dog {:db/index true}}
    (let [tx-log (atom [])
          conn (db/conn)]

      (is (map? @conn) "DB is an atom")

      (mem/listen! conn ::basic #(swap! tx-log conj (:datoms %2)))

      (mem/transact! conn [{:db/id "herman"}])

      (mem/transact! conn [{:db/id "herman" :occupation "teacher"}])

      (is (= (->clj (last @tx-log))
             [["herman" :occupation "teacher" nil]])
          "Tx-log listener called with datoms")

      (is (= {:db/id "herman" :occupation "teacher"} (db/get "herman"))
          "Entity is returned as it was inserted")

      (is (= "herman" (:db/id (db/get "herman")))
          "Entity is returned with :db/id attribute")

      (is (= "teacher" (db/get "herman" :occupation))
          "api/get an attribute of an entity")

      (is (= 1 (count (db/where [[:occupation "teacher"]])))
          "Query on non-indexed attr")

      (mem/transact! conn [{:db/id "fred" :occupation "teacher"}])

      (is (= 2 (count (db/where [[:occupation "teacher"]])))
          "Verify d/insert! and query on non-indexed field")

      (mem/transact! conn [[:db/retract "herman" :occupation]])

      (is (nil? (db/get "herman" :occupation))
          "Retract attribute")

      (is (= [["herman" :occupation nil "teacher"]]
             (->clj (last @tx-log)))
          "retraction datom")

      (is (= 1 (count (db/where [[:occupation "teacher"]])))
          "Retract non-indexed field")

      (is (nil? (db/get "herman"))
          "Entity with no attributes is removed")

      (is (false? (contains? (get-in conn [:ave :db/id]) "herman"))
          "Index has been removed")

      (mem/transact! conn [{:db/id "me"
                            :dog "herman"
                            :name "Matt"}])
      (mem/transact! conn [{:db/id "me"
                            :dog nil}])

      (is (empty? (db/where [[:dog "herman"]]))
          "Setting a value to nil is equivalent to retracting it")

      (is (not (contains? @(read/entity conn "me") :dog))
          "Nil in a map removes ")

      #_(is (= :error (try (mem/transact! conn [[:db/add "fred" :db/id "some-other-id"]])
                           nil
                           (catch js/Error e :error)))
            "Cannot change :db/id of entity")

      (testing "map txs are merged"
        (db/with-conn {}
          (db/transact! [{:db/id "a"
                          :name/first "b"
                          :name/last "c"}])
          (db/transact! [{:db/id "a"
                          :name/first "b1"}])
          (is (= (db/get "a" :name/last) "c")))))))

(deftest empty-maps
  (db/with-conn {}
    (db/transact! [[:db/add 1 :letter "a"]
                   {:db/id 2}])
    (is (nil? (db/get 2))))
  (db/with-conn {}
    (db/transact! [[:db/add 1 :letter "a"]
                   [:db/add 2 :db/id 2]])
    (is (nil? (db/get 2)))))

(deftest refs
  (let [schema {:owner (merge schema/ref
                              schema/unique-value)}]
    (db/with-conn (doto (mem/create-conn schema)
                    (mem/transact! [{:db/id "fred"
                                     :name "Fred"}
                                    {:db/id "ball"
                                     :name "Ball"
                                     :owner "fred"}]))
      (is (= {:name "Fred"
              :_owner [{:db/id "ball"}]}
             (db/pull '[* :_owner] "fred"))
          "reverse refs")))

  (db/with-conn (doto (mem/create-conn {:authors {:db/valueType :db.type/ref
                                                  :db/cardinality :db.cardinality/many}
                                        :pet {:db/valueType :db.type/ref}})
                  (mem/transact! [{:db/id "fred"
                                   :name "Fred"
                                   :pet {:db/id "fido" :name "Fido"}}
                                  {:db/id "mary"
                                   :name "Mary"}
                                  {:db/id "1"
                                   :name "One"
                                   :authors #{"fred" "mary"}}
                                  {:db/id "herman"
                                   :pet {:db/id "silly"}}
                                  #_[:db/add "1" :authors #{"fred" "mary"}]]))
    (is (= {:name "Fred"
            :_authors [{:db/id "1"}]
            :pet {:db/id "fido"}}
           (db/pull '[* :_authors] "fred"))
        "refs with cardinality-many")
    (is (= {:db/id "fido" :name "Fido"}
           (db/get "fido")))
    (is (nil? (db/get "silly"))))

  (db/with-conn {:person/pet schema/ref
                 :pet/name schema/unique-id
                 :person/name schema/unique-id}
    (db/transact! [{:person/name "Sue"
                    :person/pet {:pet/name "Sup"}}
                   {:person/name "Bob"}])
    (is (nil? (:person/pet (db/entity [:person/name "Bob"]))))
    (is (some? (:person/pet (db/entity [:person/name "Sue"]))))
    (is (= 1 (count (db/where [:person/name
                               (complement :person/pet)]))))
    (is (= 1 (count (db/where [:person/name
                               :person/pet]))))
    (is (nil? (:person/pet (db/pull [:person/pet] [:person/name "Bob"]))))
    (is (some? (:person/pet (db/pull [:person/pet] [:person/name "Sue"]))))))

(deftest touch-refs

  (db/with-conn {:children (merge schema/ref
                                  schema/many)
                 :child (merge schema/ref
                               schema/one)}
    (db/transact! [{:db/id "A"
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

    (is (= (db/pull '[:db/id {:child :...}] "B")
           {:db/id "B"
            :child {:db/id "B1"
                    :child {:db/id "B2"}}}))

    (is (= [{:db/id "B1"}]
           (-> (db/pull [:_child] "B2")
               :_child)))

    (is (= {:db/id "A.0"}
           (-> (db/pull [:children] "A")
               :children first)))

    (is (= {:db/id "A.1"}
           (-> (db/pull [{:children 1}] "A")
               :children first :children first)))

    (is (= {:db/id "A.2"}
           (-> (db/pull [{:children 2}] "A")
               :children first :children first :children first)))

    (is (= [{:db/id "A.4" :children []}]
           (-> (db/pull [:db/id {:children :...}] "A")
               :children first :children first :children first :children first :children)))))

(deftest custom-db-operations
  ;; add an operation by
  (db/with-conn {:db/tx-fns {:db/times-ten
                                      (fn [_db [_ e a v]]
                                        [[:db/add e a (* 10 v)]])}}
    (db/transact! [[:db/times-ten :matt :age 3.9]])
    (is (= 39.0 (db/get :matt :age))
        "Custom db/operation can be specified in schema"))

  (db/with-conn {:db/tx-fns {:db/times (fn [db [_ e a v]]
                                                  [[:db/add e a (* (:multiplier (mem/get-entity db :times) 0) v)]])}}
    (db/transact! [[:db/add :times :multiplier 10]
                   [:db/times :matt :age 3.9]])
    (is (= 39.0 (db/get :matt :age))
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
  (db/with-conn (doto (mem/create-conn {:children (merge schema/many
                                                         schema/ave)})
                  (mem/transact! [{:db/id "fred"
                                   :children #{"pete"}}]))
    (let [conn (db/conn)]

      (is (true? (contains? (get-in @conn [:ave :children]) "pete"))
          "cardinality/many attribute can be indexed")

      ;; second child
      (db/transact! [[:db/add "fred" :children "sally"]])


      (is (= #{"sally" "pete"} (db/get "fred" :children))
          "cardinality/many attribute returned as set")
      (is (= #{"fred"}
             (ids (db/where [[:children "sally"]]))
             (ids (db/where [[:children "pete"]])))
          "look up via cardinality/many index")

      (testing "remove value from cardinality/many attribute"
        (db/transact! [[:db/retract "fred" :children "sally"]])
        (is (= nil (ids (db/where [[:children "sally"]])))
            "index is removed on retraction")
        (is (= #{"fred"} (ids (db/where [[:children "pete"]])))
            "index remains for other value")
        (is (= #{"pete"} (db/get "fred" :children))
            "attribute has correct value"))

      (db/transact! [{:db/id "fred" :children #{"fido"}}])
      (is (= #{"fido"} (db/get "fred" :children))
          "Map transaction replaces entire set")

      (testing "retracting entity with cardinality/many attribute"
        (db/transact! [[:db/retractEntity "fred"]])
        (is (= nil (db/get "fred"))))

      (testing "unique attrs, duplicates"

        (db/with-conn {:ssn schema/unique-id
                       :email schema/unique-id
                       :pets (merge schema/many
                                    schema/unique-value)}

          ;; cardinality single
          (db/transact! [[:db/add "fred" :ssn "123"]])
          (is (= "fred" (:db/id (db/get [:ssn "123"]))))
          (is (throws #(db/transact! [[:db/add "herman" :ssn "123"]]))
              "Cannot have two entities with the same unique attr")

          ;; cardinality many
          (db/transact! [[:db/add "fred" :pets "fido"]])
          (is (= "fred" (:db/id (db/get [:pets "fido"]))))
          (is (throws #(db/transact! [[:db/add "herman" :pets "fido"]]))
              "Two entities with same unique :db.cardinality/many attr")
          (is (throws #(db/transact! [{:db/id "herman"
                                       :pets #{"fido"}}]))
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

    (->> (read/entity conn [:db/ident :person/current])
         :permission/_person
         (filter (comp #{:permission.ability/manage
                         :permission.ability/edit}
                       :permission/ability))
         (map (comp :profile/name :group/profile :permission/group))
         )
    )
  )



(deftest where-queries
  (db/with-conn {:person/id (merge
                             schema/unique-id
                             schema/ae)
                 :pet/id schema/unique-id
                 :person/pets (merge schema/many
                                     schema/ref)}
    (db/transact! [{:db/id 1
                    :person/id 1
                    :name "1"
                    :pets #{[:pet/id 1.1]}}
                   {:db/id 1.1
                    :pet/id 1.1 :name "1.1"}
                   {:db/id 2
                    :person/id 2 :name "2"}
                   {:db/id 2.1
                    :pet/id 2.1 :name "2.1"}])

    (is (= #{1 2} (ids (db/where [:person/id]))))
    (is (= #{1.1 2.1} (ids (db/where [:pet/id]))))
    (is (= #{1 2} (get-in @(db/conn) [:ae :person/id])))

    ))

(deftest equality
  (db/with-conn {:email schema/unique-id}
    (db/transact! [{:db/id "a"
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
  (db/with-conn {}
    (let [entity (db/entity 0)
          m {:a 1}]

      (is (= m (meta (with-meta entity m))))

      (is (= {} (-> entity
                    (with-meta m)
                    (vary-meta dissoc :a)
                    meta))))))



(deftest reverse-lookups
  (db/with-conn {:pet schema/ref
                 :tag-id schema/unique-id}
    (db/transact! [{:db/id "owner"
                    :pet [:tag-id "f1"]}
                   {:tag-id "f1"}])

    (is (some? (-> (db/entity [:tag-id "f1"])
                   :_pet
                   first
                   deref))))

  (db/with-conn {:system/id schema/unique-id
                 :system/notifications {:db/valueType :db.type/ref
                                        :db/cardinality :db.cardinality/many}}
    (let [s (util/random-uuid)
          n (util/random-uuid)]
      (db/transact! [{:system/id s :system/notifications #{[:system/id n]}}])
      (db/transact! [{:notification/kind :sms :system/id n}])
      (is (= 1 (count (:system/notifications (db/entity [:system/id s]))))))))

(deftest merge-schema
  (let [conn (mem/create-conn)]
    (mem/merge-schema! conn {:my/unique schema/unique-id
                             :my/ref schema/ref})
    (mem/transact! conn [{:my/unique 1
                          :my/ref {:my/unique 2 :my/name "Mr. Unique"}
                          :my/other {:my/unique 3}}])
    (is (= Entity
           (-> (read/entity conn [:my/unique 1])
               :my/ref
               type)))

    (is (= "Mr. Unique"
           (-> (read/entity conn [:my/unique 1])
               :my/ref
               :my/name)))

    (is (map?
         (-> (read/entity conn [:my/unique 1])
             :my/other)))))

(deftest add-missing-attribute
  (let [conn (mem/create-conn)]
    (mem/merge-schema! conn {:my/ref schema/ref})
    (is (mem/ref? (mem/get-schema @conn :my/ref)))
    (swap! conn mem/add-missing-index :my/ref :ae)
    (is (mem/ref? (mem/get-schema @conn :my/ref)))
    (is (some #{{:my/ref schema/ae}}
              (-> @conn :schema :db.internal/runtime-changes)))))

(deftest upsert-reverse
  (db/with-conn {:name schema/unique-id
                 :pets (merge schema/ref
                              schema/many)}
    (db/transact! [{:name "Peter"}
                   {:name "Mr. Rabbit"
                    :_pets [[:name "Peter"]]}])
    (is (= 1 (-> (db/entity [:name "Peter"])
                 :pets
                 count))
        "upsert-reverse: lookup ref")

    (db/transact! [{:name "Mr. Porcupine"
                    :_pets [{:db/id [:name "Sally"]
                             :hair-color "brown"}]}
                   {:name "Sally"
                    :car-color "red"}])

    (is (= {:name "Sally"
            :hair-color "brown"
            :car-color "red"
            :pets [{:name "Mr. Porcupine"}]}

           (db/pull '[* {:pets [:name]}] [:name "Sally"]))
        "upsert-reverse: map")

    (db/transact! [{:db/id -1
                    :name "Joe"}
                   {:name "Mr. Beaver"
                    :_pets [-1]}])

    (is (= "Mr. Beaver"
           (-> (db/entity [:name "Joe"])
               :pets
               first
               :name))
        "upsert-reverse: db/id")))

(deftest upsert

  (db/with-conn {:system/name schema/unique-id
                 :system/id schema/unique-id
                 :person/worst-friend schema/ref
                 :person/best-friend schema/ref}
    (db/transact! [{:db/id -1
                    :person/name "Matt"
                    :person/worst-friend [:system/id 1]
                    :person/best-friend [:system/name "B"]}])
    (is (throws #(db/transact! [{:system/id 1
                                 :system/name "B"}]))
        "Lookup ref upsert gotcha: if we upsert two lookup refs separately, which refer to the same entity, that entity must
         already be transacted otherwise the lookup refs will resolve to different entities.")))

(deftest component
  (db/with-conn {:profile (merge schema/ref
                                 schema/component)
                 :profiles (merge schema/ref
                                  schema/component
                                  schema/many)
                 :friends (merge schema/ref
                                 schema/many)
                 :name (merge schema/unique-id)
                 }
    (db/transact! [{:db/id 1 :name "Ivan" :profile 3}
                   {:db/id 3 :email "@3"}
                   {:db/id 4 :email "@4"}])

    ;; 1. touch an entity -> isComponent keys are inlined
    (is (= (db/touch (db/entity 1))
           {:name "Ivan", :profile {:email "@3", :db/id 3}, :db/id 1})
        "Touch inlines isComponent entities")

    (db/transact! [[:db/retractEntity 1]])

    (is (nil? @(db/entity 3))
        "Retract isComponent refs when retracting entity")

    (is (not (throws #(db/transact! [{:db/id 5
                                      :name "foo"
                                      :profile {:name "Bar"}}])))
        "Upsert isComponent ref without unique attribute")

    (is (throws #(db/transact! [{:db/id 5
                                 :friends [{:nickname "foo"}]}]))
        "Cannot upsert a non-component ref without a unique attribute")

    (db/transact! [[:db/retract 5 :profile [:name "Bar"]]])

    (is (nil? @(db/entity [:name "Bar"]))
        "Retract isComponent ref when retracting attribute")))

(deftest schema-data
  (db/with-conn {}
    (db/merge-schema! {:a (merge {:foo :bar}
                                 s/unique-id)})
    (is (= :bar (:foo (mem/get-schema @(db/conn) :a))
           (:foo (db/get [:db/ident :a])))
        "Arbitrary keys are retained on a schema entry")
    (is (mem/unique? (mem/get-schema @(db/conn) :a)))
    (is (= :db.unique/identity (db/get [:db/ident :a] :db/unique)))))
