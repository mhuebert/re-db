(ns re-db.reactivity-test
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [clojure.test :refer [deftest is are testing #?(:cljs async)]]
            [re-db.api :as db]
            [re-db.in-memory :as mem]
            [re-db.in-memory.local-state :refer [local-state]]
            [re-db.integrations.reagent.context :as context]
            [re-db.reactive :as r]
            [re-db.schema :as schema]
            [re-db.read :as read]
            [re-db.test-helpers :refer [throws]]
            [re-db.memo :as memo]
            [re-db.hooks :as hooks]
            [re-db.xform :as xf]
            [re-db.triplestore :as ts]
            [re-db.sync :as sync]
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
     (db/with-conn {}
       (let [log (atom [])
             counter (atom 0)
             conn (db/conn)
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
       (let [conn (mem/create-conn {})
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
       (let [conn (mem/create-conn {})
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

(db/with-conn {}

  (db/transact! [{:db/id :matt
                  :name/first "Matt"
                  :name/last "Huebert"}
                 [:db/add :matt :pets #{"kona"}]])

  (db/transact! [[:db/add :matt :name/middle "Neil"]]))

(def eval-count (atom 0))

(deftest listen
  (r/session
   (db/with-conn {:x schema/ref}
     (let [log (atom [])
           conn (db/conn)]
       (r/reaction! (read/depend-on-triple! conn 1 nil nil)
                    (swap! log conj 1))
       (r/reaction! (read/depend-on-triple! conn 2 :a nil)
                    (swap! log conj 2))
       (r/reaction! (read/depend-on-triple! conn nil nil 99)
                    (swap! log conj 3))
       (swap! log empty)

       (db/transact! [[:db/add 1 :name "a"]])
       (is (= @log [1]))

       (db/transact! [[:db/add 2 :b "b"]])
       (is (= @log [1]))

       (db/transact! [[:db/add 2 :a "a"]])
       (is (= @log [1 2]))

       (db/transact! [[:db/add 3 :x 99]])
       (is (= @log [1 2 3]))))))

(defn captured-patterns []
  (->> @r/*captured-derefs*
       (into #{} (map (comp :pattern meta)))))

(deftest lookup-patterns
  (db/with-conn {:a/id {:db/unique :db.unique/identity}
                 :b/id {:db/unique :db.unique/identity}}
    (testing
     (let [get-patterns (fn [f]
                          (let [res (atom nil)
                                rx (r/session @(r/reaction (f) (reset! res (captured-patterns))))]
                            @res))]
       (are [f patterns]
         (= (get-patterns f) patterns)

         #(db/get 1)
         #{[1 nil nil]}

         #(db/get [:a/id 1])
         #{[nil :a/id 1]}

         #(db/get [:a/id nil])
         #{}

         #(db/get [:a/id [:b/id 1]])
         #{[nil :b/id 1]}

         #(db/transact! [{:db/id "b" :b/id 1}])
         #{}

         #(db/get [:a/id [:b/id 1]])
         #{[nil :a/id "b"]
           [nil :b/id 1]}))))

  (testing "lookup ref pattern"

    (db/with-conn {}
      (throws (db/get [:person/children "peter"])
              "Lookup ref must be on unique attribute"))

    (db/with-conn {:person/children schema/unique-value}

      (throws (db/transact! [[:db/add "mary" :person/children "peter"]
                             [:db/add "sally" :person/children "peter"]])
              "Enforced uniqueness in cardinality/many")

      (is (nil? (db/get [:person/children "peter"]))
          "Lookup ref returns nil when attr is unique but no data found"))

    (db/with-conn {:person/children (merge schema/many
                                           schema/unique-value)}

      (db/transact! [{:db/id "peter" :name "Peter"}])
      (r/session
       (let [log (atom 0)]
         @(r/make-reaction
            (db/bound-fn []
              (db/get [:person/children "peter"])
              (swap! log inc)))
         (is (= 1 @log))
         (db/transact! [[:db/add "mary" :person/children "peter"]])
         (is (= 2 @log)))))))

(deftest read-from-reaction
  (db/transact! [{:db/id 1 :name \a}
                 {:db/id 2 :name \b}])
  (is (= #{\a \b}
         (into #{} (map :name) (db/where [:name]))))
  (is (= #{\a \b}
         (into #{} (map :name) (db/where [:name])))))

(deftest memo
  (r/session

   ;; source atom
   (def a (r/atom 0))

   ;; consumer subscriptions

   (memo/def-memo $a #(r/reaction @a))
   (swap! a inc)

   (is (= @a
          @($a)))))


(comment
 (deftest pattern-listeners
   (db/with-conn {:person/children {:db/cardinality :db.cardinality/many
                                    :db/unique :db.unique/identity}}
     (let [conn (db/conn)
           tx-log (atom [])
           _ (mem/listen! conn ::pattern-listeners #(swap! tx-log conj (:datoms %2)))]
       (testing "entity pattern"
         (db/transact! [{:db/id "mary"
                         :name "Mary"}
                        [:db/add "mary"
                         :person/children #{"john"}]
                        {:db/id "john"
                         :name "John"}])

         (reagent/flush)

         (is (= "Mary" (db/get [:person/children "john"] :name))
             "Get attribute via lookup ref")

         (let [entity-call (atom 0)
               attr-call (atom 0)]
           (reagent/track! #(do (db/get "mary")
                                (swap! entity-call inc)))
           (reagent/track! #(do (db/get "mary" :name)
                                (swap! attr-call inc)))
           (is (= 1 @entity-call @attr-call))
           (db/transact! [[:db/add "mary" :name "MMMary"]])
           (reagent/flush)
           (is (= 2 @entity-call @attr-call))
           (db/transact! [[:db/add "mary" :age 38]])
           (reagent/flush)
           (is (= [3 2] [@entity-call @attr-call]))
           "Entity listener called when attribute changes"))))))



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
            (let [[v v!] (hooks/use-ref 0)]
              (v! inc)))]
    (is (= (r/session @rx) 1))
    (r/compute! rx)
    (is (= @rx 1)
        "Without an owner resent, a reaction disposes itself after every read.
        It begins again with fresh state.")

    (r/reaction!
     (is (= @rx 1))
     (r/compute! rx)
     (is (= @rx 2) "With an owner present, a reaction persists across time.")))
  )

#?(:clj
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
            result))))))

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
     (= @!b 2)))

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
  #?(:clj (try-eval
           #(r/sorted-dependents :a (rev {:a []
                                          :h [:g]
                                          :c [:b :a]
                                          :d [:c :a]
                                          :e [:d :a]
                                          :f [:a]
                                          :g [:e]
                                          }))))

  (defn test-sort
    "Tests to see if sort-fn is a topological sort of the graph
     defined by root and get-dependents."
    [sort-fn root get-dependents]

    )

  ;;
  ;; evaluate in a thread with timeout

  )

(deftest error-propagation

  (let [e (ex-info "error" {})
        n (r/atom 1)
        a (r/reaction (if (even? @n)
                        (throw e)
                        @n))
        b (r/reaction @a)
        c (r/reaction @b)
        d (r/catch c (partial hash-map :error))]
    (is (= 1 @n
           @a @b @c
           (second (r/deref-result a)) (second (r/deref-result b)) (second (r/deref-result c))
           @d)
        "Value propagates")
    (swap! n inc)

    (is (= e
           (r/peek a) (r/peek b) (r/peek c)
           (first (r/deref-result a))
           (first (r/deref-result b))
           (first (r/deref-result c))
           (:error (r/peek d)))
        "Error propagates from source to dependents")
    (is (thrown? #?(:clj Exception :cljs js/Error) @c)
        "Dereferencing a reaction with an error throws an exception"))
  (let [a (r/reaction (throw (ex-info "error" {})))]
    (is (nil?
         (do (add-watch a ::x (fn [& _]))
             (remove-watch a ::x)
             nil))
        "Adding a watch to a reaction that throws does not throw an exception")))

(deftest xf
  (let [a (r/reaction 0)
        b (xf/map inc a)]
    (is (= 1 @b))
    (swap! a inc)
    (is (= 2 @b) "xf/transform is reactive"))

  (let [a (r/atom 0)
        b (r/reaction (if (odd? @a)
                        (throw (ex-info "whoa" {}))
                        @a))
        c (xf/map inc b)
        d (r/catch c (constantly ::caught))
        e (xf/into [] c)]
    (is (= [1] @e) "xf/into init")
    (is (= 0 @b))
    (swap! a inc)
    (is (thrown? #?(:clj Exception :cljs js/Error) @c) "xf/transform throws errors")
    (is (= @d ::caught))

    (is (thrown? #?(:clj Exception :cljs js/Error) @e) "xf/into init")
    (swap! a inc)
    (is (= 3 @c @d) "xf/transform recovers from errors")
    (is (= [1 3] @e) "xf/transform does not pass errors to xforms")))

(deftest become

  (let [-a (r/atom 1)
        a (r/reaction @-a)
        b (r/reaction @a)
        c (r/reaction @b)
        d (r/reaction @-a)]
    (is (= 1 @a @b @c @d))
    (r/become a (r/reaction (inc @-a)))
    (is (= 2 @a @b @c))
    (is (= 1 @d)))

  (let [-a (r/atom 1)
        a (xf/map inc -a)
        b (xf/map inc a)
        c (xf/map inc b)]
    (is (= [1 2 3 4]
           (mapv deref [-a a b c])))
    (r/become -a (r/atom 2))
    (mapv deref [-a a b c])
    (is (= [2 3 4 5]
           (mapv deref [-a a b c])))
    (r/become a (xf/map (partial + 2) -a))
    (is (= [2 4 5 6]
           (mapv deref [-a a b c]))))

  (r/session
   (let [-a (r/atom 1)
         $af (memo/memoize (fn [n] (r/reaction (+ @-a n))))]
     (is (= 2 @($af 1)))
     (memo/reset-fn! $af (fn [n] (r/reaction (+ @-a n n))))
     (is (= 3 @($af 1)))))
  (r/session
   ;; TODO - test hook/dispose behavior
   (let [-a (r/atom 1)
         a (xf/map inc -a)
         b (xf/map inc a)]
     (is (= 2 @a))
     (swap! -a inc)
     (is (= 3 @a))
     (r/become b (r/reaction (+ @a 2)))
     (is (= @b 5))
     (r/become a (xf/map inc -a) #_(r/reaction (+ @-a 1)))
     (is (= (inc @-a) @a))))

  (r/session
   (let [a (r/atom 1)
         b (xf/map inc a)]
     (is (= (inc @a) @b))))

  (r/session
   (let [a (r/reaction 1)
         b (xf/map inc a)
         c (r/catch (xf/map inc b) identity)]
     (is (= (inc @a) @b))
     (r/become a (r/reaction 2))
     (is (= (inc @a) @b))
     (is (= (inc @b) @c))))
  )

(deftest reaction-xforms
  (let [a (r/reaction {:xf (dedupe)} 0)
        a's (xf/into [] a)]
    (is (= [0] @a's))
    (dotimes [n 3] (swap! a inc))
    (is (= [0 1 2 3] @a's)))

  (let [a (r/atom 0)
        a's (xf/into [] a)]
    @a's
    (reset! a 1)
    (reset! a 1)
    (reset! a 2)
    (is (= [0 1 2] @a's)))

  (let [a (r/reaction {:xf (dedupe)} 0)
        a's (xf/into [] a)]
    (reset! a 0)
    (reset! a 0)
    (is (= [0] @a's)))
  )

(deftest hooks

  (r/session
   (let [a (atom 1)
         b (xf/transform a (clojure.core/map inc))]
     (is (= @b 2))))


  (r/session
   (let [source (atom 1)
         before-after (xf/transform source (xf/before:after))]
     (is (= [nil 1] @before-after))))

  (r/session
   (let [source (atom 0)
         before-after (xf/transform source (xf/before:after))]
     (is (= [nil 0] @before-after))
     (swap! source inc)
     (is (= [0 1] @before-after))))


  (r/session
   (let [source (atom 0)
         pairs (xf/transform source
                 (xf/before:after)
                 (take 5)
                 (xf/into []))]
     @pairs
     (dotimes [_ 3]
       (swap! source inc))
     (is (= [[nil 0]
             [0 1]
             [1 2]
             [2 3]] @pairs))))

  (r/session
   (let [source (r/reaction 0)
         evens (xf/transform source
                 (filter even?)
                 (take 3)
                 (xf/into []))]
     (swap! source inc)
     @evens
     (swap! source inc)
     @evens

     ))


  (let [st (r/make-step-fn (comp (dedupe)
                                 (take 3)
                                 (map identity)))]
    (is (= [(st 1)
            (st 2)
            (st 2) ;; no-op
            (st 3)
            (st 4)]
           [1
            2
            ::r/no-op
            3
            ::r/done]))))

(let [-a (r/atom 1)
      a (xf/map inc -a)]
  (r/become a (xf/map dec -a)))

(let [-a (r/atom 1)
      a (xf/map inc -a)
      b (xf/map inc a)
      c (xf/map inc b)]
  (is (= [1 2 3 4]
         (mapv deref [-a a b c])))
  (r/become -a (r/atom 2))
  (mapv deref [-a a b c])
  (is (= [2 3 4 5]
         (mapv deref [-a a b c])))
  (r/become a (xf/map (partial + 2) -a))
  (is (= [2 4 5 6]
         (mapv deref [-a a b c]))))