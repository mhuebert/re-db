(ns re-db.protocols)

(defprotocol ITriple
  (db [this])
  (eav [this e a])
  (ave [this a v])
  (vae [this v a])
  (ae [this a])
  (internal-e [this e])
  (as-map [this e])
  (get-schema [this a])
  (ref? [this a] [this a schema])
  (unique? [this a] [this a schema])
  (many? [this a] [this a schema])
  (doto-triples [this handle-triple report]))

(defn get-db [conn the-db] (or the-db (db conn)))