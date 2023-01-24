(ns re-db.reactivity-test
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [clojure.test :refer [deftest is are testing #?(:cljs async)]]
            [re-db.api :as api]
            [re-db.in-memory :as db]
            [re-db.in-memory.local-state :refer [local-state]]
            [re-db.integrations.reagent.context :as context]
            [re-db.reactive :as r]
            [re-db.schema :as schema]
            [re-db.read :as read]
            [re-db.test-helpers :refer [throws]]
            [re-db.memo :as memo]
            [re-db.hooks :as hooks]
            [re-db.xform :as xf]
            #?@(:cljs [[reagent.core :as reagent :refer [track!]]
                       [reagent.dom :as rdom]
                       [reagent.ratom :as ratom]
                       [re-db.integrations.reagent]])))

#?(:cljs
   (def dom-root (or (js/document.getElementById "rtest")
                     (let [el (-> (js/document.createElement "div")
                                  (j/!set :id "rtest"))]
                       (js/document.body.appendChild el)
                       el))))

#?(:cljs
   (deftest reagent-compat
     (let [a (r/atom 0)
           r (ratom/make-reaction (fn [] @a) :auto-run true)]
       (is (= @r 0))
       (swap! a inc)
       (is (= @r 1))
       (swap! a inc)
       (is (= @r 2)))))

#?(:cljs
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
             "Local state has unique defaults and mutation results")))))

#?(:cljs
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
         (rdom/render [component] dom-root)))))

#?(:cljs
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
                                           [component])) dom-root)))))

(defonce rx (atom nil))

#?(:cljs
   (defn ^:dev/before-load start
     ([] (some-> @rx reagent/dispose!))
     ([f]
      (start)
      (reset! rx (reagent/track! f)))))

(api/with-conn {}

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
       (r/reaction! (read/depend-on-triple! conn 1 nil nil)
                    (swap! log conj 1))
       (r/reaction! (read/depend-on-triple! conn 2 :a nil)
                    (swap! log conj 2))
       (r/reaction! (read/depend-on-triple! conn nil nil 99)
                    (swap! log conj 3))

       (api/transact! [[:db/add 1 :name "a"]])
       (is (= @log [1 2 3 1]))

       (api/transact! [[:db/add 2 :b "b"]])
       (is (= @log [1 2 3 1]))

       (api/transact! [[:db/add 2 :a "a"]])
       (is (= @log [1 2 3 1 2]))

       (api/transact! [[:db/add 3 :x 99]])
       (is (= @log [1 2 3 1 2 3]))))))

(defn captured-patterns []
  (->> @r/*captured-derefs*
       (into #{} (map (comp :pattern meta)))))

(deftest lookup-patterns
  (api/with-conn {:a/id {:db/unique :db.unique/identity}
                  :b/id {:db/unique :db.unique/identity}}
    (testing
     (let [get-patterns (fn [f]
                          (let [res (atom nil)
                                rx (r/session @(r/reaction (f) (reset! res (captured-patterns))))]
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

      (throws (api/transact! [[:db/add "mary" :person/children "peter"]
                              [:db/add "sally" :person/children "peter"]])
              "Enforced uniqueness in cardinality/many")

      (is (nil? (api/get [:person/children "peter"]))
          "Lookup ref returns nil when attr is unique but no data found"))

    (api/with-conn {:person/children (merge schema/many
                                            schema/unique-value)}

      (api/transact! [{:db/id "peter" :name "Peter"}])
      (r/session
       (let [log (atom 0)]
         @(r/make-reaction
           (api/bound-fn []
             (api/get [:person/children "peter"])
             (swap! log inc)))
         (is (= 1 @log))
         (api/transact! [[:db/add "mary" :person/children "peter"]])
         (is (= 2 @log)))))))


(deftest read-from-reaction
  (api/transact! [{:db/id 1 :name \a}
                  {:db/id 2 :name \b}])
  (is (= #{\a \b}
         (into #{} (map :name) (api/where [:name]))))
  (is (= #{\a \b}
         (into #{} (map :name) (api/where [:name])))))

(deftest memo
  (r/session

   ;; source atom
   (def a (r/atom 0))

   ;; consumer subscriptions

   (memo/def-memo $a #(r/reaction @a))
   (swap! a inc)

   (is (= @a
          @($a)))))

(deftest disposal


  (let [!latch (atom 0)]
    (r/session
     @(r/reaction
       (hooks/use-effect
        (fn []
          (swap! !latch inc)
          #(swap! !latch inc)))))
    (is (= 2 @!latch)
        "use-effect hook is disposed"))

  (let [!latch (atom 0)
        rx (r/reaction (hooks/use-effect
                        (fn []
                          (swap! !latch inc)
                          #(swap! !latch inc))))]
    (r/session @rx)
    (r/session @rx)
    (is (= @!latch 4) "Same reaction can be instantiated multiple times"))

  (let [!latch (atom 0)
        rx (r/reaction (hooks/use-effect
                        (fn []
                          (swap! !latch inc)
                          #(swap! !latch inc))))]
    (doseq [i (range 3)]
      (add-watch rx i identity)
      (remove-watch rx i))
    (is (= @!latch 6) "Reaction can be started and stopped multiple times via watch/unwatch"))

  (let [rx (r/reaction
            (let [[v v!] (hooks/use-volatile 0)]
              (v! inc)))]
    (is (= (r/session @rx) 1))
    (r/compute! rx)
    (is (= @rx 1)
        "Without an owner resent, a reaction disposes itself after every read.
        It begins again with fresh state.")

    (r/reaction!
     (is (= @rx 1))
     (r/compute! rx)
     (is (= @rx 2) "With an owner present, a reaction persists across time."))))


(comment
 (deftest pattern-listeners
   (api/with-conn {:person/children {:db/cardinality :db.cardinality/many
                                     :db/unique :db.unique/identity}}
     (let [conn (api/conn)
           tx-log (atom [])
           _ (db/listen! conn ::pattern-listeners #(swap! tx-log conj (:datoms %2)))]
       (testing "entity pattern"
         (api/transact! [{:db/id "mary"
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
           (api/transact! [[:db/add "mary" :name "MMMary"]])
           (reagent/flush)
           (is (= 2 @entity-call @attr-call))
           (api/transact! [[:db/add "mary" :age 38]])
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


(deftest disposal

  (let [!latch (atom 0)]
    (r/session
     @(r/reaction
       (hooks/use-effect
        (fn []
          (swap! !latch inc)
          #(swap! !latch inc)))))
    (is (= 2 @!latch)
        "lazy (default) reaction is disposed"))
  (let [!latch (atom 0)]
    (r/session
     (def r (r/reaction!
             (hooks/use-effect
              (fn []
                (swap! !latch inc)
                #(swap! !latch inc))))))
    (is (= 1 @!latch)
        "independent reaction is not disposed")
    (r/dispose! r))

  (let [!latch (atom 0)]
    (def !latch (atom 0))
    (memo/def-memo $sub
      (fn []
        (r/reaction
         (hooks/use-effect
          (fn []
            (swap! !latch inc)
            #(swap! !latch inc))))))

    (r/session @($sub))
    (is (= @!latch 2) "Session immediately disposes"))

  (let [!latch (atom 0)]
    (let [rx (r/reaction (hooks/use-effect
                          (fn []
                            (swap! !latch inc)
                            #(swap! !latch inc))))]
      (r/session @rx)
      (r/session @rx)
      (is (= @!latch 4) "Same reaction can be instantiated multiple times")))
  )

(defn try-eval
  "Evaluates f in a new thread with timeout"
  ([f] (try-eval f 2000))
  ([f ms]
   (let [!result (future
                  (try
                    (f)
                    (catch Exception e e)))]
     (let [result (deref !result ms (ex-info "timeout" {:ms ms}))]
       (if (instance? Exception result)
         (throw result)
         result)))))

(deftest glitch-tests
  (r/session
   (let [a (r/atom 1)
         b (r/reaction (+ @a 100))
         c (r/reaction (+ @b @a))
         c-values (xf/into [] c)]
     @c-values
     (swap! a inc)
     (is (= @c-values [102 104]))))

  (r/session
   (let [!a (r/atom 1.0)
         !rounded (r/reaction (Math/round @!a))
         !values (doto (xf/into [] !rounded) deref)]
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (swap! !a + 0.3)
     (is (= @!values [1 2])
         "evaluations occur in topological order, but changes
          do not propagate from unchanged nodes.")))

  (r/session
   (let [!a (r/atom 1)
         !b (doto (r/reaction @!a) deref)]
     (swap! !a inc)
     (prn @!a)))

  (r/session
   (let [a (r/atom 1)

         ;; depts of a
         b (r/reaction (* @a 2))

         c (r/reaction (* @a 20))
         cc (r/reaction (* @c 200))

         ;; f is first evaluated after `b` changes, while `e` has not been marked stale because it epends on `d` which is not an immediate dependent of `a`
         f (r/reaction (+ @b @cc))

         f-values (r/detach! (xf/into #{} f))]
     (swap! a inc)

     (is (= (count @f-values) 2) "glitches are avoided at depth")
     {:values @f-values}))

  (defn rev [m]
    (reduce-kv (fn [m k v]
                 (reduce #(update %1 %2 (fnil conj []) k) m v)) {} m))
  (rev {:a [:b :c]})
  (try-eval
   #(r/sorted-dependents :a (rev {:a []
                                  :h [:g]
                                  :c [:b :a]
                                  :d [:c :a]
                                  :e [:d :a]
                                  :f [:a]
                                  :g [:e]
                                  })))

  (defn test-sort
    "Tests to see if sort-fn is a topological sort of the graph
     defined by root and get-dependents."
    [sort-fn root get-dependents]

    )

  ;;
  ;; evaluate in a thread with timeout

  )

