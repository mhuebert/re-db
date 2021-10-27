# Re-DB

Currently ALPHA (routine change/breakage). Thanks to [NextJournal](https://nextjournal.com) for supporting development.

Re-DB is (attempts to be) a fast, reactive client-side triple-store for handling global state in ClojureScript apps. It is inspired by [Datomic](https://www.datomic.com) and [DataScript](https://github.com/tonsky/datascript) and works in conjunction with [Reagent](https://reagent-project.github.io). 

There are a number of similar projects targeting ClojureScript (Asami, DataScript, DataHike) which also deserve your attention. Out-of-the-box I couldn't found anything that is simultaneously fast enough for my use-cases and also reactive in a fine-grained way, so that small changes to data result in small updates to the view layer. 

Re-db focuses on three aspects of performance:

1. Load a large(ish) initial dataset - this is in the "critical path" of pageload and is a frequently-encountered problem with alternatives. 
2. Update views after a transaction - minimize the work required to determine what needs to re-render.
3. Read from the database - currently we only support relatively simple & fast lookups, observing that many common use-cases don't require or benefit from the greater expressivity of datalog.

How are we doing?

Roughly speaking we're seeing 5-10x faster transactions than DataScript (so, bringing a 800-1000ms tx down to 100-200ms), and "queries" that are 5-30x faster (but this is _not_ apples-to-apples, because we only support/encourage lookups vs the complex queries of datalog). As we don't yet support ordered indexes, some queries are still slow.

## Background

Some of the motivating ideas behind this (style of) library are:

- Data is modelled as a collection of entities (clojure maps)
- Entities can point to each other, forming a graph.
- Attributes can be indexed, to speed up reads (at the cost of slower transactions).
- Unique attributes can be used to identify entities.
- Attributes can be plural (:db.cardinality/many), in which case we store a set of values, each of which is indexed.
- A "reading" layer tracks fine-grained "patterns" that are accessed in the db, for efficient "invalidation" of views when new data is transacted.

## Usage

The schema format is consistent with DataScript.

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
    [:db/retract <id> <attribute> <value (optional)>]

    ;; retract an entity
    [:db/retractEntity <id>]

    ;; usage

    (d/transact! [[:db/add 1 :name "Matt"]])
    ```

`d/transact!` returns a "tx-report" containing `:db-before`, `:db-after`, and `:datoms`. `:datoms` can be used to move forward and backward through time by transacting `[:db/datoms <...>]` and `[:db/datoms-reverse <...>]`.

### Transaction functions

Transaction functions can be specified in the schema under `:db/tx-fns`. The function will be passed the currently `db` and the operation (vector) and must return a new list of operations.

```clj 
;; schema 
{:db/tx-fns {:my-op (fn [db [_ & args]] ...)}}

;; transaction
(d/transact! [[:my-op ...]])

```

### Entity api

Like Datomic and DataScript, re-db has an `Entity` type. Unlike the alternatives, our entity type wraps the db _connection_ rather than a snapshot; this is because we want to use entities within Reagent views and expect the entity to update/invalidate when the db changes.

What you can do with an entity:

- Dereference to get the raw entity map - `@person`. 
- `touch` it to get a raw map with all relationships included (as ids or Entity instances)
- Look up an attribute - `(:person/name person)`
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

An entity pattern read `[e _ _]` is logged.

Read an attribute by passing an ID and attribute to `d/get`.

```clj
(d/get 1 :name)
;; => "Matt"
```

An entity-attribute pattern read `[e a _]` is logged.

### Reactivity with Reagent

The `re-db.read` namespace (which sits behind `re-db.api`) integrates with Reagent so that views become bound to whatever data they consume.

Behind the scenes, we track the "patterns" observed by the view, and match these on transaction-reports.

    Pattern         Description
    `[e _ _]`       the entity with id `e`
    `[e a _]`       an entity-attribute pair
    `[_ a v]`       an attribute-value pair
    `[_ a _]`       an attribute
    `[_ _ v]`       a value - (this pattern is only used for ref attributes)

To register a callback to be fired when a pattern invalidates (unnecessary for the reagent integration), call `d/listen` with a list of patterns (see the above table for supported patterns - supply `nil` for `_`), along with a function. The provided function will called at most once per transaction and receives only the bound database `conn` as an argument. `d/listen` returns an "unlisten" function for stopping the subscription.

Examples:

```clj
;; entity
(d/listen [[1 nil nil]] #(println "The entity with id 1 was changed"))

;; entity-attribute
(d/listen [[1 :name nil]] #(println "The :name attribute of entity 1 was changed"))

;; attribute-value
(d/listen [[nil :name "Matt"]] #(println "The value 'Matt' has been removed or added to the :name attribute of an entity"))

;; attribute
(d/listen [[nil :name nil]] #(println "A :name attribute has been changed"))

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

Clauses are evaluated in order and joined using `clojure.set/intersection` (`AND`),
with early termination when the result set is empty.

## Todo

[X] a way to log the "currently subscribed" patterns at any point in a reaction
[X] add indexes on-the-fly based on usage - this means less up-front schema, + potential performance advantages (frontloading all index-building makes initial pageload slower - this can be spread out & done on-demand)
[X] ability to move forward/backward in time via the `datoms` returned in a transaction
[x] fn to clone a db without copying listeners
- allow to specify a sort-order for an ave index(?)
- convention for using re-db for local component state

-

## Possible directions

- Tab<>Worker sync - Re-DB runs in a worker, and also in each browser tab. Worker-DB persists state in IndexedDB. Purpose is to (A) maintain consistent state across tabs, and (B) support some degree of offline use. Not all attributes should be persisted to the worker/server.
- Client<>Server sync - Re-DB runs in the client, and an adapter runs in the server to support integration with eg. Datomic or XTDB(Crux).
- An effect system
