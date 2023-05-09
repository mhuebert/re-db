(ns re-db.react
  (:require #?(:cljs ["react" :as react])
            #?(:cljs ["use-sync-external-store/with-selector" :as with-selector])
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-db.reactive :as r]
            [re-db.util :refer [sci-macro]])
  #?(:cljs (:require-macros re-db.react)))

(defn useEffect [f deps] #?(:cljs (react/useEffect f deps)))
(defn useCallback [f deps] #?(:cljs (react/useCallback f deps)))
(defn useMemo [f deps] #?(:cljs (react/useMemo f deps)))
(defn useReducer [f init] #?(:cljs (react/useReducer f init)))
(defn useRef [init] #?(:cljs (react/useRef init)))
(defn useSyncExternalStoreWithSelector [subscribe get-snapshot server-snapshot selector isEqual]
  #?(:cljs
     (with-selector/useSyncExternalStoreWithSelector
      subscribe
      get-snapshot
      server-snapshot
      selector
      isEqual)))

(defn use-deps
  "Wraps a value to pass as React `deps`, using a custom equal? check (default: clojure.core/=)"
  ([deps] (use-deps deps =))
  ([deps equal?]
   #?(:cljs
      (let [counter (react/useRef 0)
            prev-deps (react/useRef deps)
            changed? (not (equal? deps (j/get prev-deps :current)))]
        (j/assoc! prev-deps :current deps)
        (when changed? (j/update! counter :current inc))
        (array (j/get counter :current))))))


(defn use-derefs* [f]
  #?(:cljs
     (let [!derefs (useCallback (volatile! r/init-derefs) (cljs.core/array))
           out (binding [r/*captured-derefs* (doto !derefs (vreset! r/init-derefs))] (f))
           subscribe (useCallback (fn [changed!]
                                    (doseq [ref @!derefs] (add-watch ref !derefs (fn [_ _ _ _] (changed!))))
                                    #(doseq [ref @!derefs] (remove-watch ref !derefs)))
                                  #js[(str/join "-" (map goog/getUid @!derefs))] #_(use-deps @!derefs))] ;; re-subscribe when derefs change (by identity, not value)
       (useSyncExternalStoreWithSelector subscribe
                                         #(mapv r/peek @!derefs) ;; get-snapshot (reads values of derefs)
                                         nil ;; no server snapshot
                                         identity ;; we don't need to transform the snapshot
                                         =) ;; use Clojure's = for equality check
       out)))

(sci-macro
 (defmacro use-derefs
   ;; "safely" subscribe to arbitrary derefs
   [& body]
   `(use-derefs* (fn [] ~@body))))