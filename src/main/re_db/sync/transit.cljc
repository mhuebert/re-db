(ns re-db.sync.transit
  (:require [cognitect.transit :as t]
            [re-db.api :as d])
  #?(:clj (:import (java.io ByteArrayOutputStream ByteArrayInputStream))))

(deftype TaggedValue [tag rep])

(defn entity-pointer [id]
  (TaggedValue. "re-db/entity" (if (vector? id)
                                 id
                                 [:db/id id])))

(def read-handlers {"re-db/entity"
                    (t/read-handler
                     (fn [e]
                       (let [[a v] e]
                         (d/entity (if (= a :db/id)
                                     v
                                     [a v])))))})

(def write-handlers
  {TaggedValue (t/write-handler #(.-tag ^TaggedValue %) #(.-rep ^TaggedValue %))})

#?(:cljs (def writer (t/writer :json {:handlers write-handlers})))
(defn pack [x] #?(:cljs (t/write writer x)
                  :clj (let [out (ByteArrayOutputStream. 4096)
                             writer (t/writer out :json {:handlers write-handlers})]
                         (t/write writer x)
                         (String. (.toByteArray out)))))

#?(:cljs (def reader (t/reader :json {:handlers read-handlers})))
(defn unpack [x] #?(:clj  (let [in (ByteArrayInputStream. (.getBytes x))]
                            (t/read (t/reader in :json)))
                    :cljs (t/read reader x)))

(comment
 (unpack (pack {:x 1})))