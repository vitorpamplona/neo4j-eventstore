# neo4j-eventstore

A graph projection of the Nostr events held by
[vespa-eventstore](https://github.com/NosFabrica/vespa-eventstore) in
[vespa-relay](https://github.com/NosFabrica/vespa-relay), queried through Cypher.

- **Nodes.** Every event, user and address is a node.
- **Relationships.** Every reference an event makes is a relationship typed by what it MEANS —
  `ROOT`, `PARENT`, `REACTED`, `FOLLOW`, `REPORTED_USER`, `ZAP_SENDER`, `SUBJECT` — decided once
  per Quartz event class (the [link vocabulary](docs/vocabulary.md)). Queries can therefore join
  event kinds, e.g. the notes my follows zapped, a thread's whole reply tree, or the users a
  NIP-85 provider ranks highly.
- **Not a relay.** Neo4j is not a relay and holds no event bodies. Vespa stays the source of
  truth: Neo4j follows Vespa's writes and removals and is reconciled against it, and Cypher
  results are hydrated from Vespa.

**Status:** built and wired into vespa-relay (`GRAPH_PROJECTION=on`). The production scale study
and rollout are next; see [`docs/plan.md`](docs/plan.md).

## Documentation

- [`docs/schema.md`](docs/schema.md): the public schema (2.0), for anyone writing Cypher. It
  covers labels, relationship types and their properties, curated values and the example queries.
- [`docs/vocabulary.md`](docs/vocabulary.md): the link vocabulary — the model, its rules, every
  relation — and [`docs/vocabulary-appendix.md`](docs/vocabulary-appendix.md), the per-class
  review of every Quartz event class.
- [`docs/spec.md`](docs/spec.md): how edges are derived, how the projection stays exact (the
  feed, the apply rules, the reconciler, the bulk load), and the guarded read-only Cypher
  endpoint.
- [`docs/plan.md`](docs/plan.md): the phases and their status.

## Using it

```kotlin
val graph = GraphProjection.open(url, user, password, source = mySourceOfTruth)
// The live feed: graph.listener's onPut/onRemove match vespa-eventstore's IndexObserver.
VespaEventStore.open(vespaUrl, observers = listOf(graph.asIndexObserver()))
graph.reconcileLoop.start(scope)                  // in ONE process: repairs drift against the source
graph.cypher.query(CypherRequest(query, params))  // guarded, read-only
```

vespa-relay's `common/…/graph/GraphWiring.kt` is the reference wiring, including the
three-line `asIndexObserver()` adapter.

## Building

```bash
./gradlew build                          # compile + unit tests + spotlessCheck (no Docker)
./gradlew spotlessApply                  # formatting + MIT header; run before committing
./gradlew :benchmark:test -Pintegration  # real Neo4j via testcontainers (needs Docker)
./gradlew :benchmark:run --args="events.jsonl out/"   # bulk-load CSVs + the neo4j-admin command
```

Pass `-DitNeo4j=bolt://host:7687` to run the integration tests against an existing server
instead of a container. `BulkImportIT` still needs a fresh container of its own.
