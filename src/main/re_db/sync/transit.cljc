(ns re-db.sync.transit
  (:require [cognitect.transit :as t]
            [re-db.api :as db])
  #?(:clj (:import (java.io ByteArrayOutputStream ByteArrayInputStream))))

(defrecord Entity [k id])

(def write-handlers {Entity (t/write-handler "re_db.sync.transit.Entity" (juxt :k :id))})
(def read-handlers {"re_db.sync.transit.Entity" (t/read-handler #?(:clj  db/entity
                                                                   :cljs (comp db/entity vec)))})

#?(:cljs (def writer (t/writer :json {:handlers write-handlers})))
(defn pack [x] #?(:cljs (t/write writer x)
                  :clj  (let [out (ByteArrayOutputStream. 4096)
                              writer (t/writer out :json {:handlers write-handlers})]
                          (t/write writer x)
                          (String. (.toByteArray out)))))

#?(:cljs (def reader (t/reader :json {:handlers (merge read-handlers {"u" cljs.core/uuid})})))
(defn unpack [x] #?(:clj  (t/read
                           (t/reader (ByteArrayInputStream. (.getBytes x))
                                     :json
                                     {:handlers read-handlers}))
                    :cljs (t/read reader x)))

(comment
  (unpack (pack (ex-info "foo" {})))
  (unpack (pack {:x 1}))
  (pack (->Entity 1))
  (unpack (pack (->Entity 2))))