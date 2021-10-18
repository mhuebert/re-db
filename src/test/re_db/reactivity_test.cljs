(ns re-db.reactivity-test
  (:require [cljs.test :refer-macros [deftest is are testing]]
            [re-db.api :as api]
            [re-db.core :as db]
            [re-db.read :as read :refer [create-conn]]
            [re-db.reagent :refer [captured-patterns]]
            [re-db.schema :as schema]
            [reagent.core :as r])
  (:require-macros [re-db.test-helpers :refer [throws]]))

(defonce rx (atom nil))

(defn ^:dev/before-load start
  ([] (some-> @rx r/dispose!))
  ([f]
   (start)
   (reset! rx (r/track! f))))

(api/with-conn {}

  (start (api/bound-fn []
                       (prn :name/first (api/get :matt :name/first))
                       (prn :name/last (api/get :matt :name/last))
                       (prn :pets (api/get :matt :pets))))

  (api/transact! [{:db/id :matt
                   :name/first "Matt"
                   :name/last "Huebert"}
                  [:db/add :matt :pets #{"kona"}]])

  (api/transact! [[:db/add :matt :name/middle "Neil"]]))

(def eval-count (atom 0))

(deftest listen
  (api/with-conn {}
    (let [log (atom nil)]
      (api/listen [[1 nil nil]] #(reset! log 1))
      (api/listen [[2 :a nil]] #(reset! log 2))

      (api/transact! [[:db/add 1 :name "a"]])
      (r/flush)
      (is (= @log 1))

      (api/transact! [[:db/add 2 :b "b"]])
      (r/flush)
      (is (= @log 1))

      (api/transact! [[:db/add 2 :a "a"]])
      (r/flush)
      (is (= @log 2))

      )))

(deftest lookup-patterns
  (api/with-conn {:a/id {:db/unique :db.unique/identity}
                  :b/id {:db/unique :db.unique/identity}}
    (testing
     (let [get-patterns (fn [f]
                          (let [res (atom nil)
                                rx (r/track! (fn [] (f) (reset! res (captured-patterns))))]
                            (r/flush)
                            (r/dispose! rx)
                            @res))]

       (are [f patterns]
         (= (get-patterns f) patterns)

         #(api/get 1)
         #{[1 nil nil]}

         #(api/get [:a/id 1])
         #{[nil :a/id 1]}

         #(api/get [:a/id nil])
         #{}

         #(api/get [:a/id [:b/id 1]])
         #{[nil :b/id 1]}

         #(api/transact! [{:db/id "b" :b/id 1}])
         #{}

         #(api/get [:a/id [:b/id 1]])
         #{[nil :a/id "b"]
           [nil :b/id 1]}))))

  (testing "lookup ref pattern"

    (api/with-conn {}
      (throws (api/get [:person/children "peter"])
              "Lookup ref must be on unique attribute"))

    (api/with-conn {:person/children schema/unique-value}

      (throws (api/transact! [[:db/add "mary" :person/children #{"peter"}]
                              [:db/add "sally" :person/children #{"peter"}]])
              "Enforced uniqueness in cardinality/many")

      (is (nil? (api/get [:person/children "peter"]))
          "Lookup ref returns nil when attr is unique but no data found"))

    (api/with-conn {:person/children (merge schema/many
                                            schema/unique-value)}

      (api/transact! [{:db/id "peter" :name "Peter"}])

      (let [log (atom 0)]
        (r/track!
         (api/bound-fn []
                       (api/get [:person/children "peter"])
                       (swap! log inc)))
        (r/flush)
        (is (= 1 @log))
        (api/transact! [[:db/add "mary" :person/children #{"peter"}]])
        (r/flush)
        (is (= 2 @log))))))

(comment
 (deftest pattern-listeners
   (let [conn (read/create-conn {:person/children {:db/cardinality :db.cardinality/many
                                                   :db/unique :db.unique/identity}})
         tx-log (atom [])
         _ (db/listen! conn ::pattern-listeners #(swap! tx-log conj (:datoms %2)))]
     (testing "entity pattern"
       (db/transact! conn [{:db/id "mary"
                            :name "Mary"}
                           [:db/add "mary"
                            :person/children #{"john"}]
                           {:db/id "john"
                            :name "John"}])

       (r/flush)

       (is (= "Mary" (read/get conn [:person/children "john"] :name))
           "Get attribute via lookup ref")

       (let [entity-call (atom 0)
             attr-call (atom 0)]
         (r/track! #(do (read/get conn "mary")
                        (swap! entity-call inc)))
         (r/track! #(do (read/get conn "mary" :name)
                        (swap! attr-call inc)))
         (is (= 1 @entity-call @attr-call))
         (db/transact! conn [[:db/add "mary" :name "MMMary"]])
         (r/flush)
         (is (= 2 @entity-call @attr-call))
         (db/transact! conn [[:db/add "mary" :age 38]])
         (r/flush)
         (is (= [3 2] [@entity-call @attr-call]))
         "Entity listener called when attribute changes")))))

#_(deftest reactivity
    (let [reader (reify
                   r/IRecompute
                   (-recompute! [_]))]
      (api/transact! [{:db/id 1
                       :name "Peter"}
                      {:db/id 2
                       :name "Victoria"}])

      (testing "capture access patterns"

        (is (do
              (r/with-dependency-tracking! {:reader reader} (api/entity 1))
              (= #{1} (-> (get-in @r/dependencies [reader api/*current-conn*])
                          :e__)))
            "entity pattern")


        (is (do (r/with-dependency-tracking! {:reader reader}
                                             (api/get 1 :name)
                                             (api/get 1 :name))
                (= #{[1 :name]} (-> (get-in @r/dependencies [reader api/*current-conn*])
                                    :ea_)))
            "entity-attr pattern")

        (is (= #{[1 :name]
                 (do
                   (r/with-dependency-tracking! {:reader reader}
                                                (api/get 1 :name)
                                                (api/get 1 :dog))
                   [1 :dog])} (-> (get-in @r/dependencies [reader api/*current-conn*])
                                  :ea_))
            "two entity-attr patterns")

        (is (= {:e__ #{1}
                :ea_ #{[1 :name]}}
               (do
                 (r/with-dependency-tracking! {:reader reader}
                                              (api/get 1 :name)
                                              (api/entity 1)
                                              (api/get 1 :name))
                 (-> (get-in @r/dependencies [reader api/*current-conn*])
                     (select-keys [:e__ :ea_]))))
            "entity pattern"))))

#_(deftest compute

    (testing "Can set a computed property"

      (api/transact! [[:db/add :db/settings :name "Herman"]])

      (d/compute! [:db/settings :name-x-2]
                  (swap! eval-count inc)
                  (apply str (take 2 (repeat (api/get :db/settings :name)))))

      (is (= "HermanHerman" (api/get :db/settings :name-x-2)))
      (is (= 1 @eval-count))

      (testing "Update a computed value when a dependent value changes"
        (api/transact! [[:db/add :db/settings :name "Lily"]])
        (is (= "LilyLily" (api/get :db/settings :name-x-2)))
        (is (= 2 @eval-count)))

      (testing "Change a computed value"

        (d/compute! [:db/settings :name-x-2]
                    (swap! eval-count inc)
                    (apply str (interpose " - " (take 2 (repeat (api/get :db/settings :name))))))

        (is (= "Lily - Lily" (api/get :db/settings :name-x-2)))
        (api/transact! [[:db/add :db/settings :name "Marvin"]])
        (is (= "Marvin - Marvin" (api/get :db/settings :name-x-2))))

      (testing "If a computed property references itself, does not cause loop"

        ;; TODO
        ;; model a proper computed-value graph to avoid cycles
        ;; supply prev-value as `this`?

        (d/compute! [:db/settings :name-x-2]
                    (swap! eval-count inc)
                    (str (api/get :db/settings :name-x-2) " x " (api/get :db/settings :name)))

        (is (= "Marvin - Marvin x Marvin" (api/get :db/settings :name-x-2)))
        (api/transact! [[:db/add :db/settings :name "Wow"]])
        (is (= "Marvin - Marvin x Marvin x Wow" (api/get :db/settings :name-x-2))))


      (testing "Clear a computed value"
        (d/compute! [:db/settings :name-x-2] false)
        (api/transact! [[:db/add :db/settings :name "Veronica"]])
        (is (= 6 @eval-count)))))
