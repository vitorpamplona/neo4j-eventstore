# neo4j-eventstore — implementation plan

Companion to [`spec.md`](spec.md); section references (§) point there. It was rewritten
2026-09-29 for the graph-projection design.

Each phase has a deliverable and an **exit gate** that must be green before the next phase
depends on it. Work is tagged by repository:
- **[N]** this repo
- **[V]** vespa-eventstore
- **[Q]** Quartz (amethyst)
- **[R]** vespa-relay

```
P0 decisions + upstream ──▶ P1 scaffold ──▶ P2 derivation ──▶ P3 in-memory projection + reconciler ──▶ P4 Neo4j binding
      │                                                                                                   │
      └──▶ [V] V1 observer hook + read walks ─────────────────────────────┐                               ▼
                                                                          │                      P5 Cypher service
                                                                          ▼                               │
                                                             P6 bulk loader ◀─────────────────────────────┘
                                                                          │
                                                             P7 scale study (staging slice) ──▶ R1–R9 relay integration + production load ──▶ P8 later
```

---

## P0 — Decisions and upstream groundwork

**Confirm with the maintainer** (spec §12):

| # | Decision | Recommendation | Needed by |
|---|---|---|---|
| Q1 | Observer hook in vespa-eventstore vs the relay-side fallback | hook | V1 |
| Q5 | Repo, group and package name | rename, e.g. `nostr-graph` | P1 |
| Q3 / Q6 | Host size; id encoding | from P7 | R5 |
| Q4 | Cypher audience | `admin` first | R6 |

**[V] V1: the vespa-eventstore change** (spec §6.1), one PR:
- `IndexObserver` in the facade package, and `open(..., observers)`;
- the `ObservedEventIndex` decorator placed directly over `MeteredEventIndex(VespaEventIndex)`,
  forwarding every member, and added to `PortDecoratorsTest` and to `ModuleBoundariesTest`'s
  layer table;
- `EngineReads.visitIds(query, withKind)` and `EngineReads.visitDocsPage(query, resumeFrom,
  maxDocs)`, both read-only, with `DocRef.kind`;
- an IT asserting every mutation style reaches the observer with exactly the removed ids:
  insert, supersession (both engine paths), kind 5, vanish, expiry, `sweepOrphanScores`,
  `delete(filter)`;
- the gate: vespa-eventstore's full `-Pintegration` suite. The store's CLAUDE.md gains a line
  naming the graph projection as an observer consumer.

**[Q] Quartz fixes.** These are small PRs, each starting with a failing test. None blocks,
because the deriver shields itself (§5.3).
1. `ListEntityExt.pubKeys()` must not map `NSec` to hex. This is a private-key leak into
   `linkedPubKeys()`.
2. `QTag.parseAddressId` / `parseAddressAsHint` must accept addresses.
3. `ChannelCreateEvent.linkedEventIds()` must drop its own id.
4. (Discuss) `ZapReceiptEvent.linkedPubKeys()` should include the zap sender.

**Exit:** decisions recorded in `docs/decisions/0001-*.md`, V1 merged or in review, and the
Quartz PRs opened.

---

## P1 — Scaffold [N]

This copies vespa-eventstore's shape.

- **Gradle:**
  - `settings.gradle.kts` with `:engine`, `:projection` and `:benchmark`;
  - Spotless (ktlint plus the MIT header in `.spotless/copyright.kt`);
  - git hooks: pre-commit runs `spotlessCheck`, pre-push runs `test`.
- **`gradle/libs.versions.toml`:**
  - kotlin `2.4.0`;
  - quartz, pinned by commit with a history comment;
  - `org.neo4j.driver:neo4j-java-driver` `6.3.0`;
  - testcontainers and `org.testcontainers:neo4j`;
  - coroutines and serialization.
  - **No vespa-eventstore dependency** (spec §2).
- **Build targets:**
  - `jvmToolchain(21)`;
  - vanniktech publishing for `:engine` and `:projection`;
  - `jitpack.yml`;
  - CI with three jobs (`lint`, `build`, `integration`);
  - `create-release.yml`.
- **Guards:**
  - `ModuleBoundariesTest` holds the spec §2 layer table, and requires that a test lives in its
    class's package;
  - `PortDecoratorsTest` checks every `GraphIndex` decorator;
  - `NoEmbeddedNeo4jTest` fails if an `org.neo4j:neo4j*` server artifact is on the runtime
    classpath.
- **Verify** against the Neo4j 2026.09 docs:
  - the Community store format and its limits;
  - dynamic relationship types in `CREATE`;
  - quantified path patterns;
  - the setting names used in spec §8.2.
- **Docs:**
  - `AGENTS.md` and `CLAUDE.md` (a pointer to it);
  - the vendored `.claude/skills/` (nostr-expert, quartz-integration);
  - `docs/decisions/`.

**Exit:** `./gradlew build` is green, and all three CI jobs run.

---

## P2 — Derivation (`:engine` `schema/` + `derive/`) [N]

This phase is pure Kotlin. It is the heart of the schema.

- `schema/`:
  - label and type-name builders (`by_<k>`, `<t>_<k>`, `ref_<f>_<k>`, `_other`);
  - `KindRegistry` (Quartz `isKnownKind`, versioned);
  - `GraphPolicy` (the `:Tag` allowlist, a 256-byte cap, an optional kind exclude list that is
    empty by default, and a hash).
- `derive/`:
  - `EdgeDeriver`, implementing spec §5 steps 1–3;
  - `LinkRules` (NIP-85 `d` subjects, 10040 services, the 9735 sender, repost embeds, **nsec
    exclusion**, self-loop drop);
  - `RoleTable` (§5.3, with its stored-vs-implied flag);
  - `Extractors` (§4.3).
- **Tests:**
  - a golden test per `appendix-providers.md` row;
  - a meta-test that fails when a Quartz class implementing a provider has no fixture;
  - role tests (NIP-10 marked, positional and mixed; NIP-22 scopes; NIP-25 last-e; zaps with
    and without `P`);
  - extractor tests;
  - invariants over a random corpus: every single-letter tag lands in exactly one place or is
    dropped by policy; no self-edges; **no `nsec` ever becomes a `:User`**.

**Exit:** all of the above are green.

---

## P3 — In-memory projection and reconciler (`memory/`, `:projection` `feed/` + `reconcile/`) [N]

- `GraphIndex` port (§2). `InMemoryGraphIndex` is **the executable spec** of `apply` and
  `unapply`:
  - the stub rule;
  - supersession by `by_<k>` / `VERSION_OF`;
  - the recent-removal fence;
  - curated values set and cleared.
- `GraphFeed` (implementing the same `IndexObserver` shape as V1, with a local copy of the
  interface until V1 is published), the bounded queue, dirty hours, and `GraphProjector`.
- `SourceOfTruth` port and `MirrorReconciler`:
  - windowed merge-diff;
  - dirty → recent → full cadence;
  - a cursor file;
  - policy-change convergence.
- **Corpus.** A deterministic generator, adapted from vespa-eventstore's `NostrCorpus`, emitting
  the graph-relevant kinds:
  - NIP-10 threads, 1111 comments, 7, 6/16, 9734/9735 with an embedded request, 3, 10000,
    30000, 1984, 1985, badges, communities, 30023 + `q`, 30382 + 10040;
  - content `nostr:` links, including one `nsec1`;
  - supersessions, kind-5s and vanishes.
- **Tests:** the source is Quartz's in-memory SQLite `EventStore`, fed the corpus. Its
  accept/remove decisions drive two simulated feeds. The tests cover:
  - random interleavings;
  - dropped deliveries;
  - a remove arriving before its put;
  - policy changes.

  In every case, one reconcile must leave `InMemoryGraphIndex`'s stored set and edges equal to
  a fresh derivation of the source's state.

**Exit:** the convergence property holds over at least 1,000 seeded interleavings.

---

## P4 — Neo4j binding (`:engine` `client/`) [N]

- `SchemaInstaller`:
  - constraints and indexes (§4.1);
  - `:Meta`;
  - version checks;
  - **`assertSafeServer()`**: the procedure allowlist, CSV import off, no plugins;
  - `KindRegistryMigration` (resumable).
- `Neo4jGraphIndex`:
  - batched `UNWIND` writes;
  - sorted-key `MERGE`;
  - dynamic types;
  - the supersession and fence logic inside the same transaction;
  - `unapply` by the stub rule;
  - keyset-paged `visitIds`;
  - `edgesOf`;
  - `sweepOrphans()`.
- `MeteredGraphIndex`, transparent, forwarding every member.
- **Integration tests** (testcontainers `neo4j:2026.09-community`, self-skipping without Docker):
  - **`ProjectionIT`**: P3's corpus and interleavings through Neo4j, with `edgesOf(id)` equal to
    `InMemoryGraphIndex` for every id, and the reconciler converging;
  - **`KindRegistryMigrationIT`**;
  - a hub write-contention probe: many concurrent applies onto one popular `:User`, counting
    retries and deadlocks.

**Exit:** all green in CI's `integration` job.

---

## P5 — Cypher service (`:projection` `cypher/`) [N]

- `CypherGuard`:
  - an `EXPLAIN` pre-flight;
  - a `queryType` check;
  - a plan-operator walk (`LoadCSV`, non-allowlisted procedures and functions, `Show*` /
    `Terminate*`);
  - a read transaction.
- **No resource limits** (spec §8.2).
- `CypherService` streaming results, `ResultEncoder` (§8.3), the `Hydrator` over
  `SourceOfTruth.fetch` in batches, and the audit record.
- `docs/schema.md`, the public reference: labels, type families, properties, implied and stored
  roles, curated values, the policy, versioning, and T1–T12 in Cypher.
  `GraphProjection.schema()` backs `GET /graph/schema`.
- **Tests:**
  - **`CypherGuardIT`** (the hostile battery of §8.5, asserting an unchanged database);
  - **`ReferenceQueriesIT`** (T1–T12 and the hybrid query against the fixture graph, with known
    answers);
  - guard unit tests on captured plans.

**Exit:** both ITs are green, and `docs/schema.md` is reviewed.

---

## P6 — Bulk loader and first release (`:benchmark`) [N]

- `graphDump`:
  - reads a `SourceOfTruth` dump (V1 `visitDocsPage`) or a JSONL export;
  - runs `EdgeDeriver`;
  - resolves supersession duplicates by external sort;
  - deduplicates nodes;
  - writes CSVs in `neo4j-admin` format (one relationship file per type), and prints the
    `neo4j-admin database import full` command line.
- The catch-up procedure (§7.3 steps 1 and 4) is scripted.
- **`BulkImportIT`**: bulk import and online apply of the same corpus give identical graphs.
- `GraphProjection.open()` with every option, metrics and gauges.
- **Release:** tag `v0.1.0`.

**Exit:** green gates; the artifacts resolve from a scratch project.

---

## P7 — Scale study on real data [N]

Staging is read-only: it is a data source, never a test gate.

1. **Pull a slice** of 20–25M events from `wss://search-staging.brainstorm.world/`:
   - all kinds, for about 300k authors around the canonical observer's web of trust, so the
     graph is connected rather than randomly sampled;
   - size it with `COUNT` first;
   - page with `until` or NIP-77.
2. **Bulk-import it**, then run the live projector against a replay, and measure:
   - import time;
   - apply throughput;
   - hub retries.
3. **Measure:**
   - bytes per event and per relationship, split into node store, relationship store,
     properties, id strings and indexes;
   - relationship-type count;
   - the page-cache hit ratio vs query latency for T1–T12, warm and cold;
   - hub worst cases (the most-followed account, the most-replied note).
4. **Collect the numbers the deferred limits will be set from:**
   - result sizes;
   - memory and run time of heavy Cypher shapes;
   - how much a heavy query slows the projector.
5. **Measure the alternative** id encoding (Q6), and the per-kind share of nodes, relationships
   and bytes, so the size of each kind in the all-kinds graph is known.
6. **Extrapolate to 500M events / 62M pubkeys**, and decide:
   - Q3 (host RAM and disk);
   - Q6;
   - the full-sweep period.

**Exit:** a `benchmark/README.md` "Scale" section with the measurements and the decisions.

---

## R — vespa-relay integration and production load [R]

| Step | Work |
|---|---|
| **R1** | **Dependencies.** Bump the vespa-eventstore pin to the V1 merge (run its integration gate, as the relay's AGENTS.md requires). Add this library, pinned, and `force` the Quartz pin across modules. |
| **R2** | **Config** (`:common`): `GRAPH_PROJECTION=off\|on` (default `off`), `NEO4J_URL`, `NEO4J_USER`, `NEO4J_PASSWORD`, `NEO4J_DATABASE`, `GRAPH_QUEUE`, `GRAPH_EXCLUDE_KINDS`, `GRAPH_TAG_NODES`, `GRAPH_RECONCILE_RECENT_SECONDS`, `GRAPH_CYPHER=off\|admin\|auth\|public`. Document them in `docs/configuration.md` and `.env.example`. With `on`, the relay refuses to boot if Neo4j is unreachable after a bounded retry, or if `assertSafeServer()` fails. |
| **R3** | **Wiring.** Pass `GraphFeed` as an `observers` entry to `VespaEventStore.open()` in **both** `RelayMain` and `SyncMain`. No wrapper is needed, so the `as? VespaEventStore` casts are untouched. Implement `SourceOfTruth` over `VespaEventStore.engine`. With `off`, nothing is constructed. |
| **R4** | **Reconciler job**: `relay/…/maintenance/GraphReconcile.kt`, in the relay process only, with a cursor file (the `FtsReindex` pattern). |
| **R5** | **Neo4j host.** A separate machine sized by P7. Community `2026.09`. Bolt reachable only from the relay's network; 7474 not public. The §8.2 settings applied (`dbms.security.procedures.allowlist`, `dbms.security.allow_csv_import_from_file_urls=false`, no plugins). No timeout or memory-cap settings in v1. A compose profile `graph` exists for local development only. |
| **R6** | **Endpoints and health.** `POST /graph/cypher` behind `GRAPH_CYPHER` (`admin` reuses `AdminGate` / `Nip98AdminGate` against `RELAY_ADMIN_PUBKEYS`; `auth` accepts any valid NIP-98 signature). `GET /graph/schema`. Audit to `.audit/graph-cypher/`. A `graph` block in `/stats.json` / pulse (spec §9) and a status card. |
| **R7** | **`GraphProjectionIT`** (Vespa + Neo4j containers): the relay's ingest with injected drops and a Neo4j pause → equal id sets after one reconcile, and client `OK` latency unaffected while Neo4j is paused. |
| **R8** | **Docs:** `docs/decisions/graph-projection.md` (D1–D3), and the runbook in `docs/operations.md` (bulk load, reset, "reconcile reports N extra", policy change). A trap line in AGENTS.md: "the graph is a projection; never write to Neo4j directly". |
| **R9** | **Production rollout.** (a) Turn the projection `on` against an empty graph; note `T0`. (b) Run `graphDump` from production Vespa (500M events), then `neo4j-admin import`. (c) Catch-up reconcile. (d) Hold until the recent pass reports 0 missing / 0 extra for 7 consecutive days, one full sweep has completed clean, and the queue never overflowed. (e) `GRAPH_CYPHER=admin`. |

**Exit:** production answers T1–T12 over the full graph, and the projection has held zero
drift for a week.

---

## P8 — Later, each a separate decision

- **Query limits** (timeouts, memory, rows, concurrency, rate), set from P7 and early
  production numbers. The audience widens to `auth` / `public` after that.
- **Trust-aware views** over the `d_30382` ranks (an observer parameter that filters reached
  users).
- **`:Relay` nodes** from NIP-65 and relay hints.
- **A bounded traversal DSL** for the Nostr wire (a REQ extension or a NIP-90 DVM).
- **More extractors** and allowlisted tag names, as real queries ask for them.

---

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| vespa-eventstore declines or delays the observer hook | Low–Medium | The fallback in spec §6.4 (relay-side accepted-event feed plus local NIP-09/62) keeps the project unblocked. The reconciler bounds the extra drift. |
| The initial load of 500M events takes too long or runs out of disk | Medium | Offline bulk import. P7 measures bytes per event and per kind. The id encoding (Q6). |
| The page cache cannot hold the hot set, so queries are disk-bound | High at the full scale on modest hardware | A dedicated host sized from P7. Kind-typed relationships keep expansions narrow even when cold. |
| Hub-node write contention (popular pubkeys) | Medium | Sorted-key batches and driver retries, measured in P4 and P7. |
| **Heavy Cypher starves the projector (accepted in v1: no limits)** | Medium | The audience starts at `admin`. The reconciler repairs drift after the load passes. Limits arrive in P8 from measurements. |
| Raw Cypher used to write, read files or reach internal URLs (Community has no RBAC) | High without guards | The layered `CypherGuard` plus server settings, with `CypherGuardIT` as a CI gate. |
| Bulk DM-metadata mining (kinds 4 and 1059 are projected, so senders and recipients are queryable) | Medium | The Cypher audience starts at `admin` (Q4). The kind exclude list is available if that ever needs to change (spec §8.4). |
| A private-key leak via `nsec` in content | Certain without the rule | The deriver's exclusion, an invariant test, and the upstream Quartz fix. |
| The public schema changes and breaks users' queries | Medium | `docs/schema.md`, `GET /graph/schema`, semantic versioning, and migration notes. |
| A Quartz pin bump changes provider output | Medium | Appendix-driven golden tests turn red. `KindRegistryMigration`. |
| GPL contamination | Low | Driver-only dependency, plus `NoEmbeddedNeo4jTest`. |
