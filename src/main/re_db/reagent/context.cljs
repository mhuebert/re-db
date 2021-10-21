(ns re-db.reagent.context
  (:require ["react" :as react]
            [applied-science.js-interop :as j]
            [re-db.api :as api]
            [reagent.core :as reagent]
            [reagent.impl.component :as rcomponent])
  (:require-macros re-db.reagent.context))

;; dynamic binding in a javascript world...
;; objective: bind & then find a "global-ish" re-db database
;;
;; approach:
;; - for reading the db in views: use react context
;; -                       callbacks: use DOM ancestor lookup


;; string key where we write the conn onto a DOM node
(def dom-context-key "re-db-conn")

;; helper function to find an ancestor node that matches `pred`
(defn dom-closest [el pred]
  (if (pred el)
    el
    (when-let [parent (.-parentNode ^js el)]
      (recur parent pred))))

;; find conn associated with element by crawling ancestors
(defn element-conn [el]
  (-> el
      (dom-closest (j/get dom-context-key))
      (j/get dom-context-key)))

;; React interop
(defonce conn-context (react/createContext dom-context-key))

;; find conn associated with the given/current component
(defn component-conn
  ([] (component-conn rcomponent/*current-component*))
  ([component] (some-> component (j/get :context))))

;; monkey-patch the api/conn lookup to check the current component & currently-handled window.event
(set! api/conn
      (fn []
        (or api/*current-conn*
            (component-conn)
            (some-> js/window.event
                    .-target
                    (element-conn)))))

(defn bind-conn
  [conn body]
  (reagent/with-let [conn (api/->conn conn)
                     ref-fn #(some-> % (j/!set dom-context-key conn))]
    (react/createElement (.-Provider conn-context)
                         #js{:value conn}
                         (reagent/as-element
                          [:div {:ref ref-fn} body]))))

