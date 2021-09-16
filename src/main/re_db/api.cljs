(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace])
  (:require [re-db.core :as d]
            [re-db.read :as read]
            [re-db.macros :as m]))

(def ^:dynamic *conn* (read/create-conn {}))

(m/defpartial entity {:f '(read/entity *conn* _)}
  [id])

(m/defpartial get {:f '(read/get *conn* _)}
  ([id attr])
  ([id attr not-found]))

(m/defpartial get-in {:f '(read/get-in *conn* _)}
  ([id path])
  ([id path not-found]))

(m/defpartial select-keys {:f '(read/select-keys *conn* _)}
  [id ks])

(m/defpartial entity-ids {:f '(read/entity-ids *conn* _)}
  [qs])

(m/defpartial entities {:f '(read/entities *conn* _)}
  [qs])

(m/defpartial contains? {:f '(read/contains? *conn* _)}
  [id])

(m/defpartial touch {:f '(read/touch *conn* _)}
  [entity])

(m/defpartial transact! {:f '(d/transact! *conn* _)}
  ([txs])
  ([txs opts]))

(m/defpartial listen {:f '(read/listen *conn* _)}
  ([callback])
  ([patterns callback]))

(def merge-schema! (partial d/merge-schema! *conn*))

(def unique-id d/unique-id)
