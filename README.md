# Re-DB

Currently ALPHA.

Re-DB is a fast, reactive client-side triple-store for handling global state in ClojureScript apps. It is inspired by [Datomic](https://www.datomic.com) and [DataScript](https://github.com/tonsky/datascript) and works in conjunction with [Reagent](https://reagent-project.github.io).

re: "fast" - compared to DataScript, re-db makes different tradeoffs for performance - transactions are 5-10x faster, depending on your schema and entity size. Much of this gain is due to our data layout - entities are stored as maps, with indexes off to the side. When we transact a list of maps, not much work has to be done for keys that aren't indexed. This is especially helpful during page load, when many apps face slow down due to ingesting an initial data set from the server. (TODO: benchmark reads / query alternatives)

re: "reactive" - re-db's built-in [Reagent](https://reagent-project.github.io) support means that views update automatically when underlying data changes. Behind the scenes we track what "patterns" you're reading at a fine-grained level. Unlike prior art in this space (see [posh](https://github.com/mpdairy/posh)) we don't attempt to support datalog, only lookups and an entity api. If you're in love with datalog you probably won't like this. But I find that Datalog is often overkill for simple use-cases, where it's often harder to read than direct lookups & relationship traversals via lookups.

## Background

Some of the motivating ideas behind this (style of) library are:

- Data is modelled as a collection of entities (clojure maps)
- Entities can point to each other, forming a graph (schema: `{:db/valueType :db.type/ref}`)
- Attributes can be indexed, for fast (simple) queries (schema: `{:db/index true}`)
- Unique attributes can be used to identify entities (schema: `{:db/unique :db.unique/identity}`)
- Attributes can have "sets" as values - each value is then indexed (schema: `{:db.cardinality :db.cardinality/many}`)
- The data layer "cooperates" with a view layer so that views update (only) when dependent data changes.

The schema format is consistent with DataScript.

## Usage

It can be convenient to use just one re-db namespace, `re-db.api :as d`, for reads and writes throughout an app. This will use a database located at `re-db.api/*conn*`.

```clj
(ns my-app.core
  (:require [re-db.api :as d]))
```

### Writing data

To write data, pass a collection of transactions to `d/transact!`. There are two kinds of transactions.

1. Map transactions are a succinct way to transact entire entities. A map must have either a `:db/id` attribute, or a unique attribute. Supplying `:db/id` directly is faster.

    ```clj
    {:db/id 1
     :name "Matt"}
    ;; if :email has the schema {:db/unique :db.unique/identity}
    {:email "a@example.com"
     :name "Sophie"}


    ;; usage
    (d/transact! [{:db/id 1
                   :name "Matt"
                   :website "https://matt.is"}
                   {:email "a@example.com"
                    :website "example.com"}])
    ```

2. Vector transactions allow more fine-grained control.

    ```clj
    ;; add an attribute
    [:db/add <id> <attribute> <value>]

    ;; retract an attribute
    [:db/retract-attr <id> <attribute> <value (optional)>]

    ;; retract an entity
    [:db/retract-entity <id>]

    ;; usage

    (d/transact! [[:db/add 1 :name "Matt"]])
    ```

`d/transact!` returns a "tx-report" containing `:db-before`, `:db-after`, and `:datoms`. `:datoms` can be used to move forward and backward through time by transacting `[:db/datoms <...>]` and `[:db/datoms-reverse <...>]`.

### Entity api



### Reading data

Read a single entity's map by passing its ID to `d/entity`.

```clj
(d/get 1)
;; => {:db/id 1, :name "Matt"}
```

An entity pattern read (:e__) is logged.

Read an attribute by passing an ID and attribute to `d/get`.

```clj
(d/get 1 :name)
;; => "Matt"
```

An entity-attribute pattern read (`:ea_`) is logged.

Read nested attributes via `d/get-in`.

```clj
(d/get-in 1 [:address :zip])
```

An entity-attribute pattern read (`:ea_`) is logged.

### Reactivity with Reagent

The `re-db.read` namespace (which sits behind `re-db.api`) integrates with Reagent so that views become bound to whatever data they consume.

Behind the scenes, we track the "patterns" observed by the view, and match these on transaction-reports.

    _Pattern_            _Key_                _Description_
    id                   :e__                 the entity with `id`
    [id attr]            :ea_                 an entity-attribute pair
    [attr val]           :_av                 an attribute-value pair
    attr                 :_a_                 an attribute

To register a callback to be fired when a pattern invalidates (unnecessary for the reagent integration), call `d/listen` with a map of the form `{<pattern> [<...values...>]}` (see the above table for how values should be formatted for each pattern), along with a function. The provided function will called at most once per transaction and receives only the bound database `conn` as an argument. `d/listen` returns an "unlisten" function for stopping the subscription.

Examples:

```clj
;; entity
(d/listen {:e__ [1]} #(println "The entity with id 1 was changed"))

;; entity-attribute
(d/listen {:ea_ [[1 :name]]} #(println "The :name attribute of entity 1 was changed"))

;; attribute-value
(d/listen {:_av [[:name "Matt"]]} #(println "The value 'Matt' has been removed or added to the :name attribute of an entity"))

;; attribute
(d/listen {:_a_ [:name]} #(println "A :name attribute has been changed"))

;; call d/listen! with a single argument (listener function) to be notified on all changes
(d/listen #(println "The db has changed"))
```

### Indexes

Use `d/merge-schema!` to update indexes.

```clj
(d/merge-schema! {:children {:db/index true, :db/cardinality :db.cardinality/many}})
```

### Finding entities

Use `d/entity-ids` and `d/entities` to find entities which match a collection of predicates, each of which should be:

1. An attribute-value **vector**, to match entities which contain the attribute-value pair. If the attribute is indexed, this will be very fast. Logs an attribute-value pattern read (:_av).

```clj
(d/entity-ids [[:name "Matt"]])
;; or
(d/entities [[:name "Matt"]])
```

2. A **keyword**, to match entities that contain the keyword. Logged as an attribute pattern read (:_a_).

```clj
(d/entity-ids [:name])
;; or
(d/entities [:name])
```

3. A **predicate function**, to match entities for which the predicate returns true.

```clj
(d/entity-ids [#(= (:name %) "Matt")])
(d/entities [#(= (:name %) "Matt")])
```