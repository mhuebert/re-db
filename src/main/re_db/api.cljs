(ns re-db.api
  (:refer-clojure :exclude [get get-in contains? select-keys namespace clone])
  (:require [re-db.core :as d]
            [re-db.read :as read]
            [re-db.macros :as m])
  (:require-macros re-db.api))

(defonce ^:dynamic *current-conn* nil #_(read/create-conn {}))

(defn current-conn [] *current-conn*)

(defn conn*
  "Accepts a conn or a schema, returns conn"
  [conn-or-schema]
  (if (map? conn-or-schema)
    (read/create-conn conn-or-schema)
    conn-or-schema))

(defn clone
  "Creates a copy of conn (without listeners)"
  [conn]
  (read/listen-conn (atom (dissoc @conn :cached-readers))))

(m/defpartial entity {:f '(read/entity *current-conn* _)}
  [id])

(m/defpartial get {:f '(read/get *current-conn* _)}
  ([id])
  ([id attr])
  ([id attr not-found]))

(m/defpartial ids-where {:f '(read/ids-where *current-conn* _)}
  [qs])

(m/defpartial where {:f '(read/where *current-conn* _)}
  [qs])

(m/defpartial touch {:f '(read/touch _)}
  ([entity])
  ([entity pull]))

(m/defpartial transact! {:f '(d/transact! *current-conn* _)}
  ([txs])
  ([txs opts]))

(m/defpartial listen {:f '(read/listen *current-conn* _)}
  ([callback])
  ([patterns callback]))
