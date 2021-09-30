(ns re-db.reactivity-test
  (:require [cljs.test :refer-macros [deftest is are testing]]
            [re-db.api :as d]
            [re-db.core :as db]
            [re-db.read :as read :refer [create-conn]]
            [re-db.reagent :refer [current-patterns]]
            [reagent.core :as r])
  (:require-macros [re-db.test-helpers :refer [throws]]))

(defonce rx (atom nil))

(defn ^:dev/before-load start
  ([]
   (reset! d/*conn* @(create-conn {}))
   (some-> @rx r/dispose!))
  ([f]
   (start)
   (reset! rx (r/track! f))))

(d/merge-schema! {:pets {:db/cardinality :db.cardinality/many}})

(start
 (fn []
   (prn :name/first (d/get :matt :name/first))
   (prn :name/last (d/get :matt :name/last))
   (prn :pets (d/get :matt :pets))
   ))

(d/transact! [{:db/id :matt
               :name/first "Matt"
               :name/last "Huebert"}
              [:db/add :matt :pets #{"kona"}]])

(d/transact! [[:db/add :matt :name/middle "Neil"]])

(def eval-count (atom 0))

(deftest listen
  (let [log (atom nil)]
    (d/listen {:e__ [1]} #(reset! log 1))
    (d/listen {:ea_ [[2 :a]]} #(reset! log 2))
    (d/transact! [[:db/add 1 :name "a"]])
    (r/flush)
    (is (= @log 1))

    (d/transact! [[:db/add 2 :b "b"]])
    (r/flush)
    (is (= @log 1))

    (d/transact! [[:db/add 2 :a "a"]])
    (r/flush)
    (is (= @log 2))

    ))

(deftest lookup-patterns
  (testing
   (reset! d/*conn* @(create-conn {:a/id {:db/unique :db.unique/identity}
                                   :b/id {:db/unique :db.unique/identity}}))
    (let [get-patterns (fn [f]
                          (let [res (atom nil)
                                rx (r/track! (fn [] (f) (reset! res (current-patterns))))]
                            (r/flush)
                            (r/dispose! rx)
                            @res))]

      (are [f patterns]
        (= (get-patterns f) patterns)

        #(d/get 1)
        #{[1 nil nil]}

        #(d/get [:a/id 1])
        #{[nil :a/id 1]}

        #(d/get [:a/id nil])
        #{}

        #(d/get [:a/id [:b/id 1]])
        #{[nil :b/id 1]}

        #(d/transact! [{:db/id "b" :b/id 1}])
        #{}

        #(d/get [:a/id [:b/id 1]])
        #{[nil :a/id "b"]
          [nil :b/id 1]}))))

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
         "Entity listener called when attribute changes"))

     (testing "lookup ref pattern"

       (db/transact! conn [{:db/id "peter" :name "Peter"}])

       (let [log (atom 0)]
         (r/track!
          (fn []
            (read/get conn [:person/children "peter"])
            (swap! log inc)))
         (r/flush)
         (is (= 1 @log))
         (db/transact! conn [[:db/add "mary" :person/children #{"peter"}]])
         (r/flush)
         (is (= 2 @log)))))))

#_(deftest reactivity
  (let [reader (reify
                 r/IRecompute
                 (-recompute! [_]))]
    (d/transact! [{:db/id 1
                   :name  "Peter"}
                  {:db/id 2
                   :name  "Victoria"}])

    (testing "capture access patterns"

      (is (do
            (r/with-dependency-tracking! {:reader reader} (d/entity 1))
            (= #{1} (-> (get-in @r/dependencies [reader d/*conn*])
                        :e__)))
          "entity pattern")


      (is (do (r/with-dependency-tracking! {:reader reader}
                                           (d/get 1 :name)
                                           (d/get 1 :name))
              (= #{[1 :name]} (-> (get-in @r/dependencies [reader d/*conn*])
                                  :ea_)))
          "entity-attr pattern")

      (is (= #{[1 :name]
               (do
                 (r/with-dependency-tracking! {:reader reader}
                                              (d/get 1 :name)
                                              (d/get 1 :dog))
                 [1 :dog])} (-> (get-in @r/dependencies [reader d/*conn*])
                                :ea_))
          "two entity-attr patterns")

      (is (= {:e__ #{1}
              :ea_ #{[1 :name]}}
             (do
               (r/with-dependency-tracking! {:reader reader}
                                            (d/get 1 :name)
                                            (d/entity 1)
                                            (d/get 1 :name))
               (-> (get-in @r/dependencies [reader d/*conn*])
                   (select-keys [:e__ :ea_]))))
          "entity pattern"))))

#_(deftest compute

    (testing "Can set a computed property"

      (d/transact! [[:db/add :db/settings :name "Herman"]])

      (d/compute! [:db/settings :name-x-2]
                  (swap! eval-count inc)
                  (apply str (take 2 (repeat (d/get :db/settings :name)))))

      (is (= "HermanHerman" (d/get :db/settings :name-x-2)))
      (is (= 1 @eval-count))

      (testing "Update a computed value when a dependent value changes"
        (d/transact! [[:db/add :db/settings :name "Lily"]])
        (is (= "LilyLily" (d/get :db/settings :name-x-2)))
        (is (= 2 @eval-count)))

      (testing "Change a computed value"

        (d/compute! [:db/settings :name-x-2]
                    (swap! eval-count inc)
                    (apply str (interpose " - " (take 2 (repeat (d/get :db/settings :name))))))

        (is (= "Lily - Lily" (d/get :db/settings :name-x-2)))
        (d/transact! [[:db/add :db/settings :name "Marvin"]])
        (is (= "Marvin - Marvin" (d/get :db/settings :name-x-2))))

      (testing "If a computed property references itself, does not cause loop"

        ;; TODO
        ;; model a proper computed-value graph to avoid cycles
        ;; supply prev-value as `this`?

        (d/compute! [:db/settings :name-x-2]
                    (swap! eval-count inc)
                    (str (d/get :db/settings :name-x-2) " x " (d/get :db/settings :name)))

        (is (= "Marvin - Marvin x Marvin" (d/get :db/settings :name-x-2)))
        (d/transact! [[:db/add :db/settings :name "Wow"]])
        (is (= "Marvin - Marvin x Marvin x Wow" (d/get :db/settings :name-x-2))))


      (testing "Clear a computed value"
        (d/compute! [:db/settings :name-x-2] false)
        (d/transact! [[:db/add :db/settings :name "Veronica"]])
        (is (= 6 @eval-count)))))
