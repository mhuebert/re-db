(ns re-db.reactivity-test
  (:require [applied-science.js-interop :as j]
            [cljs.test :refer-macros [deftest is are testing async]]
            [re-db.api :as api :refer [create-conn]]
            [re-db.in-memory :as db]
            [re-db.in-memory.local-state :refer [local-state]]
            [re-db.integrations.reagent.context :as context]
            [re-db.reactive :as r]
            [re-db.schema :as schema]
            [reagent.core :as reagent :refer [track!]]
            [reagent.dom :as rdom]
            [clojure.string :as str]
            [reagent.ratom :as ratom]
            re-db.integrations.reagent
            [re-db.patterns :as patterns])
  (:require-macros [re-db.test-helpers :refer [throws]]))

(def dom-root (or (js/document.getElementById "rtest")
                  (let [el (-> (js/document.createElement "div")
                               (j/!set :id "rtest"))]
                    (js/document.body.appendChild el)
                    el)))

(deftest reagent-compat
  (let [a (r/atom 0)
        r (ratom/make-reaction (fn [] @a) :auto-run true)]
    (is (= @r 0))
    (swap! a inc)
    (is (= @r 1))
    (swap! a inc)
    (is (= @r 2))))

(deftest reagent-state
  (api/with-conn {}
    (let [log (atom [])
          counter (atom 0)
          conn (api/conn)
          component (fn [{:keys [app/id]}]
                      (let [!state (local-state ratom/*ratom-context*
                                                :conn conn
                                                :default {:i (swap! counter inc)}
                                                :key id)]
                        (swap! !state assoc :id-cap (str/capitalize id))
                        (swap! log conj [(@!state :i) id (@!state :id-cap)])
                        [:div "Hello"]))]
      (rdom/render [:div
                    [component {:app/id "a"}]
                    [component {:app/id "b"}]] dom-root)
      (reagent/flush)
      (is (= @log [[1 "a" "A"]
                   [2 "b" "B"]])
          "Local state has unique defaults and mutation results"))))

(deftest reagent-bind-dom
  (async done
    (let [conn (api/create-conn {})
          component (fn []
                      (context/bind-conn conn
                                         [:div {:ref (fn [el]
                                                       (when el
                                                         (js/setTimeout
                                                          (fn []
                                                            (is (identical? (context/element-conn el) conn)
                                                                "Conn is bound via dom node ancestry")
                                                            (prn 1)
                                                            (done))
                                                          100)))}]))]
      (rdom/render [component] dom-root))))

(deftest reagent-bind-context
  (async done
    (let [conn (api/create-conn {})
          component (reagent/create-class
                     {:context-type context/conn-context
                      :reagent-render
                      (fn []
                        (is (= (context/component-conn) conn)
                            "Conn is bound via react context")
                        (done)
                        [:div])})]
      (rdom/render (context/bind-conn conn
                                      (do
                                        (is (= conn (re-db.api/conn))
                                            "Conn is bound via dynamic var")
                                        [component])) dom-root))))

(defonce rx (atom nil))

(defn ^:dev/before-load start
  ([] (some-> @rx reagent/dispose!))
  ([f]
   (start)
   (reset! rx (reagent/track! f))))

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
  (r/session
   (api/with-conn {:x schema/ref}
     (let [log (atom [])
           conn (api/conn)]
       (r/reaction! (patterns/depend-on-triple! conn 1 nil nil)
                    (swap! log conj 1))
       (r/reaction! (patterns/depend-on-triple! conn 2 :a nil)
                    (swap! log conj 2))
       (r/reaction! (patterns/depend-on-triple! conn nil nil 99)
                    (swap! log conj 3))

       (api/transact! [[:db/add 1 :name "a"]])
       (is (= @log [1 2 3 1]))

       (api/transact! [[:db/add 2 :b "b"]])
       (is (= @log [1 2 3 1]))

       (api/transact! [[:db/add 2 :a "a"]])
       (is (= @log [1 2 3 1 2]))

       (api/transact! [[:db/add 3 :x 99]])
       (is (= @log [1 2 3 1 2 3]))))))

(deftest lookup-patterns
    (api/with-conn {:a/id {:db/unique :db.unique/identity}
                    :b/id {:db/unique :db.unique/identity}}
      (testing
       (let [get-patterns (fn [f]
                            (let [res (atom nil)
                                  rx (r/reaction! (f) (reset! res (r/captured-patterns)))]
                              (reagent/flush)
                              (reagent/dispose! rx)
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
          @(r/make-reaction
            (api/bound-fn []
              (api/get [:person/children "peter"])
              (swap! log inc)))
          (is (= 1 @log))
          (api/transact! [[:db/add "mary" :person/children #{"peter"}]])
          (is (= 2 @log))))))


(deftest read-from-reaction
  (async done
    (api/with-conn {
                    ;:name schema/ae
                    }
      (api/transact! [{:db/id 1 :name \a}
                      {:db/id 2 :name \b}])
      (is (= #{\a \b}
             (into #{} (map :name) (api/where [:name]))))
      (track! #(do (is (= #{\a \b}
                          (into #{} (map :name) (api/where [:name]))))
                   (done))))))

(comment
 (deftest pattern-listeners
   (api/with-conn (patterns/reactive-conn
                   {:person/children {:db/cardinality :db.cardinality/many
                                      :db/unique :db.unique/identity}})
     (let [conn (api/conn)
           tx-log (atom [])
           _ (db/listen! conn ::pattern-listeners #(swap! tx-log conj (:datoms %2)))]
       (testing "entity pattern"
         (db/transact! conn [{:db/id "mary"
                              :name "Mary"}
                             [:db/add "mary"
                              :person/children #{"john"}]
                             {:db/id "john"
                              :name "John"}])

         (reagent/flush)

         (is (= "Mary" (api/get [:person/children "john"] :name))
             "Get attribute via lookup ref")

         (let [entity-call (atom 0)
               attr-call (atom 0)]
           (reagent/track! #(do (api/get "mary")
                                (swap! entity-call inc)))
           (reagent/track! #(do (api/get "mary" :name)
                                (swap! attr-call inc)))
           (is (= 1 @entity-call @attr-call))
           (db/transact! conn [[:db/add "mary" :name "MMMary"]])
           (reagent/flush)
           (is (= 2 @entity-call @attr-call))
           (db/transact! conn [[:db/add "mary" :age 38]])
           (reagent/flush)
           (is (= [3 2] [@entity-call @attr-call]))
           "Entity listener called when attribute changes"))))))



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
