(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace])
  (:require [re-db.core :as d]
            [re-db.read :as read]
            [re-db.macros :as m]))

(defonce ^:dynamic *conn* (read/create-conn {}))

(m/defpartial entity {:f '(read/entity *conn* _)}
  [id])

(m/defpartial get {:f '(read/get *conn* _)}
  ([id])
  ([id attr])
  ([id attr not-found]))

(m/defpartial ids-where {:f '(read/ids-where *conn* _)}
  [qs])

(m/defpartial where {:f '(read/where *conn* _)}
  [qs])

(m/defpartial touch {:f '(read/touch *conn* _)}
  ([entity])
  ([entity pull]))

(m/defpartial transact! {:f '(d/transact! *conn* _)}
  ([txs])
  ([txs opts]))

(m/defpartial listen {:f '(read/listen *conn* _)}
  ([callback])
  ([patterns callback]))

(def merge-schema! (partial d/merge-schema! *conn*))
