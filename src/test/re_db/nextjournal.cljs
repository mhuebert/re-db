(ns re-db.nextjournal
  (:require [re-db.core :as d]
            [re-db.read :as read]))

(def conn
  (d/create-conn {:article/profile {:db/valueType :db.type/ref}
                  :article/last-edited-by {:db/valueType :db.type/ref}
                  :article/remix-of {:db/valueType :db.type/ref}
                  :article/remixed-by {:db/valueType :db.type/ref}
                  :person/profile {:db/valueType :db.type/ref}
                  :group/profile {:db/valueType :db.type/ref}
                  :group/member {:db/cardinality :db.cardinality/many
                                 :db/valueType :db.type/ref}
                  :permission/person {:db/valueType :db.type/ref}
                  :permission/group {:db/valueType :db.type/ref}
                  :permission/article {:db/valueType :db.type/ref}
                  :secret/profile {:db/valueType :db.type/ref}

                  ;; lookup idents
                  :db/ident {:db/unique :db.unique/identity} ;; used for entities with no :nextjournal/id and for :person/current identifier
                  :nextjournal/id {:db/unique :db.unique/identity}}))

(d/transact! conn [{:group/name "Groupy"
                    :db/id "g2"
                    :group/member #{"p1"}}
                   [:db/add "g2" :group/member #{"p2"}]
                   {:db/id "p1"
                    :person/name "P1"}
                   {:db/id "p2"
                    :person/name "P2"}])

(= "P1"
   (->> (read/entity conn "g2")
        :group/member
        first
        deref
        :person/name))

(d/transact! conn [{:person/name "hello" :person/address "foo 123" :nextjournal/id :someid}])

(:person/address (read/entity conn [:nextjournal/id :someid]))

(d/transact! conn [[:db/retract [:nextjournal/id :someid] :person/address "foo 123"]])
(d/transact! conn [[:db/add [:nextjournal/id :someid] :person/address "Some Nice Address"]])
