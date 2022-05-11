(ns re-db.integrations.reagent.context
  (:require ["react" :as react]
            [applied-science.js-interop :as j]
            [re-db.in-memory :as rm]
            [re-db.patterns :as patterns]
            [re-db.api :as api]
            [reagent.core :as reagent]
            [reagent.impl.component :as rcomponent])
  (:require-macros re-db.integrations.reagent.context))

;; dynamic binding in a javascript world...
;; objective: bind & then find a "global-ish" re-db database
;;
;; approach:
;; - for reading the db in views: use react context
;; -                       callbacks: use DOM ancestor lookup


;; string key where we write the conn onto a DOM node
(def context-key (str ::conn))

(defn dom-closest [el getval]
  (when el
    (if-some [v (getval el)]
      v
      (recur (.-parentNode ^js el) getval))))

;; find conn associated with element by crawling ancestors
(defn element-conn [el]
  (dom-closest el (j/get context-key)))

;; React interop
(defonce conn-context (react/createContext context-key))

;; find conn associated with the given/current component
(defn component-conn
  ([] (component-conn rcomponent/*current-component*))
  ([component] (some-> component (j/get :context))))

;; monkey-patch the api/conn lookup to check the current component & currently-handled window.event
(defonce original-conn-impl api/conn)
(set! api/conn
      (fn []
        (or (original-conn-impl)
            (component-conn)
            (some-> js/window.event
                    .-target
                    (element-conn)))))

(defn bind-conn
  [conn body]
  (reagent/with-let [conn (patterns/->conn conn)
                     ref-fn #(some-> % (j/!set context-key conn))]
    (react/createElement (.-Provider conn-context)
                         #js{:value conn}
                         (reagent/as-element
                          [:div {:ref ref-fn} body]))))

