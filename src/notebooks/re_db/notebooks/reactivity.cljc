(ns re-db.notebooks.reactivity
  (:require [mhuebert.clerk-cljs :refer [cljs]]
            [nextjournal.clerk #?(:clj :as :cljs :as-alias) clerk]
            [re-db.api :as db]
            #?(:cljs [re-db.sync.client :as sync.client]
               :clj [re-db.sync.server :as sync.server])))


;; demonstrate how queries can depend on datom patterns