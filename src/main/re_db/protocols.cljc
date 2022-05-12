(ns re-db.protocols)

(defprotocol ITriple
  (db [this])
  (eav [this e a] [this e])
  (ave [this a v])
  (vae [this v] [this v a])
  (ae [this a])
  (internal-e [this e])
  (get-schema [this a])
  (ref? [this a] [this a schema])
  (unique? [this a] [this a schema])
  (many? [this a] [this a schema])
  (doto-triples [this handle-triple report])
  (transact [this txs] [this txs options]))

(defn get-db [conn the-db] (or the-db (db conn)))