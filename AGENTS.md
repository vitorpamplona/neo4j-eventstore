# AGENTS.md

**neo4j-eventstore** keeps a Neo4j graph an exact projection of the events vespa-eventstore
holds, and serves read-only Cypher over it. It uses Kotlin 2.4 and JDK 21, with Quartz pinned by
commit in `gradle/libs.versions.toml`. Neo4j runs **no Nostr policy**: Vespa decides what is
held, and the graph follows.

## Commands

- `./gradlew build`: the CI unit gate, including `spotlessCheck`. It needs no Docker.
- `./gradlew :benchmark:test -Pintegration`: the real-Neo4j gate. Run it for anything touching
  Cypher, the schema or `client/`.
- `./gradlew spotlessApply`: run before every commit. New `.kt` files need the MIT header, which
  this adds.

## Map

- **`:engine`**, layered in this order:
  - `schema/` and `derive/`: labels, relationship-type names, the kind registry and policy, then
    event → nodes and edges;
  - the port, `GraphIndex`;
  - `metrics/`;
  - its two implementations: `memory/` (`InMemoryGraphIndex`, the **executable spec**) and
    `client/` (`Neo4jGraphIndex`, `SchemaInstaller`).
- **`:projection`**:
  - `feed/`: the live listener, which never blocks the source;
  - `reconcile/`: windowed id-set diffs against `SourceOfTruth`;
  - `cypher/`: the guard, the service and hydration;
  - `GraphProjection`: the front door.
- **`:benchmark`**: the bulk CSV writer and `finalize`, plus the integration tests.

`ModuleBoundariesTest` holds the layers, `PortDecoratorsTest` checks that a decorator overrides
every port member, and `NoEmbeddedNeo4jTest` checks that no module links the GPL server.

## Traps

- **The graph is a projection.** Never write to Neo4j except through `GraphIndex`. A fix belongs
  in derivation plus a reconcile, not in hand-written Cypher.
- **Apply must stay order-independent.** Events arrive in any order and more than once. The slot
  rule (newest `created_at`, then lowest id), the `:Removed` fence and the stubs make that work.
  The unit convergence tests shuffle histories to prove it; keep them green.
- **One write transaction per event.** Neo4j 2026.09 can fail a read of a node deleted earlier in
  the same transaction, instead of skipping it.
- **A reconcile apply is authoritative**: it bypasses the fence and displaces the incumbent.
  Without that, a stale slot whose winner sits in another window is never repaired.
- **Change `InMemoryGraphIndex` and `Neo4jGraphIndex` together.** `ProjectionIT` asserts that
  their dumps are equal.
- **Relationship type names are API** (`docs/schema.md`). A rename is a major schema version.
- **The Cypher guard walks the `EXPLAIN` plan.** Do not add a text-level check that the plan
  walk makes redundant. Any new allowlisted procedure must be read-only and must not reveal
  other databases.
- **A JUnit test with a non-`Unit` return type is silently skipped.** Write
  `fun x(): Unit = runBlocking { … }`.
- **neo4j-admin arguments:** the database name goes right after `full`, because
  `--relationships` swallows a trailing positional argument.
