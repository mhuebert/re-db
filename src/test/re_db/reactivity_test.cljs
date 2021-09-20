(ns re-db.reactivity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-db.api :as d]
            [re-db.read :refer [create-conn]]
            [reagent.core :as r])
  (:require-macros [re-db.test-helpers :refer [throws]]))

(reset! d/*conn* @(create-conn {}))

(defonce rx (atom nil))

(defn ^:dev/before-load start
  ([]
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
