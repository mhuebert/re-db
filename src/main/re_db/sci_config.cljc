(ns re-db.sci-config
  (:require [sci.core :as sci]
            [re-db.reactive :as r]
            [re-db.react]))

(defn ^:macro redef
  ([form env name rx] (r/redef:impl form env name nil rx))
  ([form env name doc rx] (r/redef:impl form env name doc rx)))

(defn ^:macro reaction [form env & args]
  (let [[options body] (r/parse-reaction-args form args)]
    `(r/make-reaction ~options (fn [] ~@body))))

(defn ^:macro reaction! [form env & args]
  (let [[options body] (r/parse-reaction-args form args {:detached true})]
    `(r/make-reaction ~options (fn [] ~@body))))

(defn ^:macro session [form env & args]
  `(r/session* (fn [] ~@args)))

(defn ^:macro var-present? [form env the-name] `(~'bound? (def ~the-name)))

(def re-db-reactive-ns (sci/create-ns 're-db.reactive nil))
(def re-db-reactive-namespace
  (merge #_(sci/copy-ns re-db.reactive re-db-reactive-ns)
         {'var-present? (sci/copy-var var-present? re-db-reactive-ns)
          'become (sci/copy-var r/become re-db-reactive-ns)
          'redef (sci/copy-var redef re-db-reactive-ns)
          'reaction (sci/copy-var reaction re-db-reactive-ns)
          'reaction! (sci/copy-var reaction! re-db-reactive-ns)
          'session (sci/copy-var session re-db-reactive-ns)
          'session* (sci/copy-var r/session* re-db-reactive-ns)
          'atom (sci/copy-var r/atom re-db-reactive-ns)
          '*owner* (sci/copy-var r/*owner* re-db-reactive-ns)}))

(defn ^:macro use-derefs [form env & body]
  `(re-db.react/use-derefs* (fn [] ~@body)))

(def re-db-react-ns (sci/create-ns 're-db.react))
(def re-db-react-namespace {'use-derefs (sci/copy-var use-derefs re-db-react-ns)})

(def namespaces
  {'re-db.reactive re-db-reactive-namespace
   're-db.react re-db-react-namespace})