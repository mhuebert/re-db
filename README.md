# Re-DB

Currently ALPHA (routine change/breakage)

Re-DB is a fast, reactive client-side triple-store for handling global state in ClojureScript apps. It is inspired by [Datomic](https://www.datomic.com) and [DataScript](https://github.com/tonsky/datascript) and works in conjunction with [Reagent](https://reagent-project.github.io).

**re: "fast"** - compared to DataScript, re-db makes different tradeoffs for performance - transactions are 5-10x faster, depending on your schema and entity size. Much of this gain is due to our data layout - entities are stored as maps, with indexes off to the side. When we transact a list of maps, not much work has to be done for keys that aren't indexed. This is especially helpful during page load, when many apps face slow down due to ingesting an initial data set from the server. (TODO: benchmark reads / query alternatives)

**re: "reactive"** - re-db's built-in [Reagent](https://reagent-project.github.io) support means that views update automatically when underlying data changes. Behind the scenes we track what "patterns" you're reading at a fine-grained level. Unlike prior art in this space (see [posh](https://github.com/mpdairy/posh)) we don't attempt to support datalog, only lookups and an entity api. This may not be your cup of tea; personally I often find Datalog to be overkill, and more difficult to read/write than lookups + core functions like filter/sort.

## Background

Some of the motivating ideas behind this (style of) library are:

- Data is modelled as a collection of entities (clojure maps)
- Entities can point to each other, forming a graph.
- Attributes can be indexed, to speed up reads (at the cost of slower transactions).
- Unique attributes can be used to identify entities.
- Attributes can be plural (:db.cardinality/many), in which case we store a set of values, each of which is indexed.
- A "reading" layer tracks fine-grained "patterns" that are accessed in the db, for efficient "invalidation" of views when new data is transacted.

Data is "addressable" independent of the runtime, programming language or program structure (eg. `(d/entity [:email "xx"])` not `some-namespace/the-person`).

The schema format is consistent with DataScript.

## Usage

For convenience we offer a `re-db.api` namespace which reads & writes to a default database (`re-db.api/*conn*`).

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

Like Datomic and DataScript, re-db has an `Entity` type. Unlike the alternatives, our entity type wraps the db _connection_ rather than a snapshot; this is because we want to use entities within Reagent views and expect the entity to update/invalidate when the db changes.

What you can do with an entity:

- Dereference it to get the raw entity map - `@person`
- Look up any attribute - `(:person/name person)`
  - if it is a relationship attribute, the result will also be wrapped as an `Entity`
- Look up reverse attributes to find other entities pointing to this one - `(:pet/_owner person)`

Reads are tracked so that the containing Reagent view only updates when observed attributes change. If you dereference an entity, the view depends on the "whole" entity.

When creating an entity from a lookup ref, eg. `(entity [:email "hello@example.com"])`, we use late binding so that the entity doesn't need to exist (yet). Can be helpful when waiting for the network.

### Reading data

(alternatives to the entity api)

Read a single entity's map by passing its ID to `d/get`.

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

    Pattern     Value        Description
    :e__        id           the entity with `id`
    :ea_        [id attr]    an entity-attribute pair
    :_av        [attr val]   an attribute-value pair
    :_a_        attr         an attribute

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

Use `d/where` to find entities that match a list of clauses, each of which should be:

1. An attribute-value **vector**, to match entities which contain the attribute-value pair. If the attribute is indexed, this will be very fast. Logs an attribute-value pattern read (:_av).

```clj
(d/where [[:name "Matt"]])
```

2. A **keyword**, to match entities that contain the keyword. Logged as an attribute pattern read (:_a_).

```clj
(d/where [:name])
```

3. A **predicate function**, to match entities for which the predicate returns true.

```clj
(d/where [#(= (:name %) "Matt")])
```
_(question - should d/where accept varargs instead of a vector?)_


Clauses are evaluated in order and joined using `clojure.set/intersection` (`AND`),
with early termination when the result set is empty.

## Todo

- a way to log the "currently subscribed" patterns at any point in a reaction
- add indexes on-the-fly based on usage - this means less up-front schema, + potential performance advantages (frontloading all index-building makes initial pageload slower - this can be spread out & done on-demand)
- more attention paid to history / time-travel with datom logs
- more tests
- more attention to "query"/filtering api (d/where)
- allow to specify a sort-order for an ave index?
- perhaps more efficient update of multi-step queries during transact can be achieved by using cached intermediate values instead of valueless invalidations