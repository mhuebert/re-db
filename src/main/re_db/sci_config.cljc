(ns re-db.sci-config
  (:require [sci.core :as sci]
            [re-db.reactive]
            [re-db.react]))

;; requires sci 0.7.39 or later (March 5, 2023)
;; (for fixes to defprotocol)

(def namespaces
  {'re-db.reactive (sci/copy-ns re-db.reactive (sci/create-ns 're-db.reactive nil))
   're-db.react (sci/copy-ns re-db.react (sci/create-ns 're-db.react nil))})