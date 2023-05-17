(ns re-db.sync.transit
  (:require [cognitect.transit :as t])
  #?(:clj (:import (java.io ByteArrayOutputStream ByteArrayInputStream))))

#?(:cljs (def writer (t/writer :json)))
(defn pack [x] #?(:cljs (t/write writer x)
                  :clj (let [out (ByteArrayOutputStream. 4096)
                             writer (t/writer out :json)]
                         (t/write writer x)
                         (String. (.toByteArray out)))))

#?(:cljs (def reader (t/reader :json {:handlers {"u" cljs.core/uuid}})))
(defn unpack [x] #?(:clj  (let [in (ByteArrayInputStream. (.getBytes x))]
                            (t/read (t/reader in :json)))
                    :cljs (t/read reader x)))

(comment
 (unpack (pack (ex-info "foo" {})))
 (unpack (pack {:x 1})))