# neo4j-eventstore — implementation plan

Companion to [`spec.md`](spec.md). Section references (§) point there.

The plan runs in phases. Each phase has a deliverable and an **exit gate** that must be green
before the next phase depends on it. Work in three repositories is tagged:

- **[N]** neo4j-eventstore
- **[V]** vespa-eventstore
- **[Q]** Quartz (amethyst)
- **[R]** vespa-relay

```
 P0 decisions + upstream ─┬─▶ P1 scaffold ─▶ P2 derivation ─▶ P3 in-memory spec ─▶ P4 Neo4j EventIndex ─▶ P5 traversal on Neo4j
                          │                                                          │                     │
                          └─▶ [V] V1 visitDocsPage ─────────────────────────────────┐│                     ▼
                                                                                    ▼▼          P6 mirror + hardening + 0.1.0
                                                                      P7 scale study (staging slice)        │
                                                                                    │                       ▼
                                                                                    └────────▶ R1–R9 vespa-relay integration ─▶ P8 later
```

---

## P0 — Decisions and upstream groundwork

**Decisions to confirm** with the maintainer before P1:

| # | Decision | Recommendation |
|---|---|---|
| Q1 | Reuse vespa-eventstore's policy layer (D1) | reuse |
| Q5 | Maven group / package | pick one |
| Q7 | Cypher endpoint audience, and whether DM-metadata kinds are excluded (D3, spec §8.2.4) | start `admin`; exclude DMs |
| Q2 / Q3 / Q4 | Body mode, hosting, wire exposure | can wait for P7 |

**[Q] Quartz fixes.** These are small independent PRs, each with a failing test first, per the
amethyst repo's "verify, don't guess" rule. The store also shields itself (§5.3), so none of them
blocks.

1. `ListEntityExt.pubKeys()`: stop mapping `NSec` to hex. This is a private-key leak into
   `linkedPubKeys()` for the 14 `citedNIP19()` classes.
2. `QTag.parseAddressId` / `parseAddressAsHint`: accept addresses. They currently reject any
   value containing `:`, which is every address.
3. `ChannelCreateEvent.linkedEventIds()`: drop the self id.
4. (Discuss) `ZapReceiptEvent.linkedPubKeys()`: include the zap sender from the embedded request.

**[V] vespa-eventstore.**

- **V1.** Expose a read-only paged full-document walk on `EngineReads` (`visitDocsPage(query,
  resumeFrom, maxDocs)`), so a dump or backfill can read Vespa's post-policy state without the
  rewrite side effects of `reindexFullTextSearch`. P7's bulk path needs it; nothing earlier does.
- **V2.** Confirm `EventIndex`, `EventDoc`, `EventQuery`, `NostrSemanticsStore`,
  `InMemoryEventIndex` and `MeteredEventIndex` are public API in the published `store`/`engine`
  artifacts. D1 depends on them staying so. Add a line to vespa-eventstore's CLAUDE.md naming
  neo4j-eventstore as a port implementer, so a port change there is known to have a second
  implementation to update.

**Exit:** decisions recorded in `docs/decisions/0001-*.md`, and the Quartz PRs opened.

---

## P1 — Repository scaffold [N]

Copy the shape of vespa-eventstore.

- **Gradle:**
  - `settings.gradle.kts` with `:engine`, `:store`, `:benchmark`, and repositories google,
    mavenCentral and jitpack.
  - The root `build.gradle.kts` applies Spotless to all projects, with ktlint and
    `licenseHeaderFile(.spotless/copyright.kt)`, where the MIT header is "Copyright (c) 2026 Vitor
    Pamplona".
  - An `installGitHook` task.
- **`gradle/libs.versions.toml` pins:**
  - kotlin `2.4.0`;
  - quartz `fcd76d2075` (the same pin as vespa-eventstore, with the pin-history comment
    convention);
  - vespa-eventstore store (JitPack commit or Maven Central `1.0.1`+);
  - `org.neo4j.driver:neo4j-java-driver` `6.3.0`;
  - testcontainers (+ `org.testcontainers:neo4j`);
  - coroutines and serialization `1.11.0`.
- **Modules:**
  - every module uses `jvmToolchain(21)`;
  - `:engine` and `:store` use vanniktech maven-publish, with `:engine` as
    `api(quartz)` + `api(vespa-eventstore engine)`;
  - `:engine` applies `java-test-fixtures`;
  - `:benchmark` uses the `application` plugin and is unpublished.
- **Tooling:**
  - `.git-hooks/pre-commit` runs `spotlessCheck`, and `pre-push` runs `test`;
  - `jitpack.yml` (JDK 21, no signing);
  - `.github/workflows/build.yml` with three jobs, `lint` / `build` / `integration`;
  - `create-release.yml` publishing on `v*` tags.
- **Guards:**
  - `ModuleBoundariesTest` (the layer table from §3, and the test-lives-with-class rule);
  - `PortDecoratorsTest` (every decorator overrides every `EventIndex` *and* `GraphIndex` fun).
  - Both read source; `:store`'s test task declares both source trees as inputs.
- **Docs:** `AGENTS.md` + `CLAUDE.md` (pointer), README sections as in vespa-eventstore,
  `docs/decisions/`, and `.claude/skills/`, the same set vespa-eventstore vendors
  (event-store-semantics, nostr-expert, quartz-integration).

**Exit:** `./gradlew build` is green with empty modules, and CI runs all three jobs (integration
self-skips with no ITs yet).

---

## P2 — Graph derivation (`:engine` `doc/`) [N]

The heart of the schema. It is pure Kotlin with no I/O.

- `KindRegistry`: the default is Quartz `EventFactory.isKnownKind`. It is versioned, and its
  version is stored in `:Meta`.
- `RelTypes`: `by_<k>`, `<t>_<k>`, `ref_<f>_<k>`, and the `_other` bucket (§4.2), with name
  escaping.
- `EdgeDeriver.derive(Event): GraphDoc`, implementing §5 steps 1–3:
  - provider sets with validation;
  - single-letter tag classification rules 1–6;
  - derived links;
  - one edge per (source, target, type), with `pos` / `relay` / `marker` / `roles` / `via` /
    `at`.
- `LinkRules`:
  - NIP-85 `d` subjects;
  - the 10040 services;
  - the 9735 sender;
  - repost embeds;
  - **nsec exclusion**;
  - self-loop drop.
- `RoleTable`: the table in §5.3, delegating to Quartz helpers.

**Tests** (unit, the bulk of this phase):

- **Golden tests.** For every row of `appendix-providers.md`, fixture JSON → the expected
  `GraphDoc`. Generate the fixtures with Quartz builders where they exist.
- **Invariant tests** over a random corpus:
  - every single-letter tag lands in exactly one indexed place (edge or `:Tag`), or is
    over-length;
  - no derived edge duplicates a literal one;
  - no edge targets the event itself;
  - no 64-hex that appears as a `nsec1…` in content ever becomes a `:User`.
- **Role tests:** NIP-10 marked, positional and mixed threads; NIP-22 root vs reply scopes;
  NIP-25 last-e; zap receipts with and without `P`.

**Exit:** all golden, invariant and role tests pass. The appendix table and the tests are
cross-checked by a test that fails if a Quartz class implementing a provider has no golden
fixture (it reflects over `EventFactory`'s known kinds).

---

## P3 — The executable spec (`:engine` `memory/`) and the traversal model [N]

- `GraphIndex` port (§3), `Traversal` model (§7.1), `TraversalResult`.
- `InMemoryGraphIndex`:
  - `EventIndex` members delegate matching to vespa-eventstore's `InMemoryEventIndex`, a single
    NIP-01 spec;
  - on `put`/`remove`, it maintains an adjacency map from `EdgeDeriver`, with the stub
    semantics of §4.1;
  - `GraphIndex.traverse` is the **reference semantics**: set per step, `PATHS` weights, `Ref`
    sets, `exclude`, `stored` filtering. It has no caps (spec §7.1).
- **Corpus.** Extend a copy of vespa-eventstore's deterministic `NostrCorpus`, generating the
  kinds the battery needs:
  - 1 with NIP-10 threads, 1111 comments, 7, 6/16, 9734/9735 with an embedded request, 3,
    10000, 30000, 1984, 1985;
  - 8/30008/10008 badges, 34550/4550 communities, 30023 + `q` quotes, 30382 + 10040;
  - content `nostr:` links, including one `nsec1`.

**Tests:**

- `NostrSemanticsStore(InMemoryGraphIndex())` passes vespa-eventstore's `ParityCheck` battery vs
  Quartz SQLite **in-process, with no Docker**. That is a unit-level parity gate.
- Traversals T1–T12 (§7.4) against hand-computed expectations on a small fixed graph.
- Stub lifecycle: reply before parent; parent deleted, the reply survives on a stub; supersession
  moves `VERSION_OF`.

**Exit:** both suites are green. The traversal semantics documented in KDoc match §7.1.

---

## P4 — Neo4j `EventIndex` (`:engine` `schema/`, `query/`, `client/`) [N]

- `SchemaInstaller`: the constraints and indexes of §4.1, and `:Meta`. It is idempotent and
  checks the schema version. It also fails fast on server version < 5.26, because dynamic types
  are required.
- `Neo4jGraphIndex` writes: batched `UNWIND` of `GraphDoc` rows, sorted-key `MERGE`, dynamic
  types, managed-transaction retries. `remove` follows the stub rule.
- `EventCypher` (§6.2): anchor selection, union-of-homes for tag literals, keyset paging for all
  streaming members, `distinctTagIndexValues` from `:Tag`, and the search-blind behaviour of
  §6.4.
- `MeteredGraphIndex` in `metrics/`, transparent and forwarding every member.

**Integration tests** (`@Tag("integration")`, testcontainers `neo4j:2026.09-community`,
self-skip without Docker, `api.version=1.41` as in vespa-eventstore):

- `Neo4jParityIT`: `ParityCheck` over `NostrSemanticsStore(Neo4jGraphIndex)` vs SQLite, 100%.
- `FilterMatrixIT` (NIP-01 subset): multi-filter REQ/COUNT pairs against a `Filter.match`
  oracle, i.e. served set == oracle and COUNT == served.
- `DerivationParityIT`: after loading the P3 corpus, `edgesOf(id)` in Neo4j equals
  `InMemoryGraphIndex.edgesOf(id)` for every event.
- `PlanShapeIT`: `PROFILE` the canonical shapes (ids, author+kind, `#p`+kind, `#e` on a
  1M-degree hub) and assert index seeks and typed expansions, with no label scans.

**Exit:** all four ITs are green in CI's `integration` job.

---

## P5 — Traversals on Neo4j (`query/TraversalCypher`, `:store` `GraphReads`) [N]

- `TraversalCypher` (§7.3):
  - a `CALL (n) {…}` subquery per step, then `DISTINCT`;
  - role → type expansion from `RoleTable` ∩ `relTypes()`;
  - `Ref` sets;
  - `PATHS` weights;
  - no limits of any kind (spec §7.1).
- `:store` `mapping/TraversalJson`: the codec for §7.2, rejecting unknown keys. `GraphReads`
  covers `traverse`, `degree`, `edgesOf`, and `explain(traversal)` (the compiled Cypher +
  `EXPLAIN` plan, for operators).
- **`TraversalParityIT`**: T1–T12, plus a randomized traversal generator (seeded), in-memory vs
  Neo4j. Result sets and `PATHS` order must be identical.

**Cypher endpoint mechanism** (spec §8.2), in `:store` `cypher/`:

- `CypherGuard`: `EXPLAIN` pre-flight, `queryType` check, plan-operator walk (`LoadCSV`,
  non-allowlisted procedures and functions, `Show*` / `Terminate*`), then execution in a read
  transaction.
- `CypherResultEncoder` (§8.2.3), with hydration under `SKELETON`.
- `SchemaInstaller.assertSafeServer()`: refuses to open when the procedure allowlist, the
  CSV-import setting is missing, or a plugin is installed.
- **`CypherGuardIT`**, the hostile battery of §8.2.5, asserting the database is unchanged.
- `docs/schema.md` (the public schema reference, with T1–T12 in Cypher) and `GraphReads.schema()`
  for `GET /graph/schema`.

**Exit:** traversal parity and `CypherGuardIT` are green, and every T-query's worst-case latency on the P3 corpus is
recorded in `benchmark/README.md`.

---

## P6 — Replication pieces, hardening, first release [N]

- `:store` `mirror/`:
  - `StoreMirror(primary, replica, queue, onDirty)`, following §9.1: `Accepted`-only
    forwarding, ordered `delete(filter)` forwarding, bounded queue, dirty-hour marking, and never
    blocking the primary;
  - `MirrorReconciler(primary, replica, cursorStore)`, following §9.2: windowed sorted-merge
    diff, missing → copy, extra → delete; dirty → recent → full cadence; resumable.
- **Convergence tests**, two `NostrSemanticsStore(InMemoryEventIndex())`s with fault injection:
  - random permutations of the same accepted stream converge;
  - dropped events are repaired by one reconcile;
  - a primary-only removal (simulating `sweepOrphanScores`) is repaired;
  - a kind-5 / vanish / supersession applied live converges;
  - a replica outage leaves the primary's latency untouched.
- `putIfNewer` engine-atomic override (§6.1), with `supersedesViaPut = true`, parity re-run.
- `KindRegistryMigration` (resumable, recorded in `:Meta`) with `SchemaMigrationIT`.
- `sweepOrphans()`.
- Metrics and gauges mirroring vespa-eventstore's `CostLedger` model.
- `Neo4jEventStore.open()` with all options.

**Release:** tag `v0.1.0`, which publishes to Maven Central and JitPack.

**Exit:** `./gradlew build` and `-Pintegration` are green, and the release artifacts resolve from
a scratch project.

---

## P7 — Scale study on real data [N] (+ [V] V1)

Staging is a data source and a sanity oracle, never a test gate. It is read-only.

1. **Pull a slice** from `wss://search-staging.brainstorm.world/`: about 10M events.
   - Take all kinds for ~200k authors around the canonical observer's web of trust, so the graph
     is connected, not a random sample.
   - Size it with `COUNT` first.
   - Page with `until` or NIP-77.
2. **Load path A (online):** `batchInsert` through the store. Measure events/s, relationships/s,
   and the deadlock/retry rate on hub nodes.
3. **Load path B (bulk):** a `:benchmark` `graphDump` tool:
   - reads Vespa via V1 (or a JSONL export);
   - runs the **same `EdgeDeriver`**;
   - writes node/relationship CSVs, with dedup by external sort;
   - then `neo4j-admin database import full`;
   - then a `MirrorReconciler` catch-up;
   - then the `DerivationParityIT` check on a sample.
4. **Measure:** store size per 1M events (graph vs bodies, `FULL` vs `SKELETON`),
   relationship-type count, and T1–T12 p50/p99 with a warm vs cold page cache. Also measure the
   hub worst cases (the most-followed account; the most-replied note).
   **Also collect what the deferred limits need** (spec §7.1, §8.2.2): the distribution of
   per-step fanout and intermediate set sizes on real hubs, the memory and run time of heavy
   traversals and Cypher shapes, and how much a heavy query slows the mirror's writes. These
   are the numbers limits will be set from.
5. **Extrapolate to 212M** and decide:
   - Q2 (body mode);
   - Q3 (hosting / RAM / separate host / kind subset);
   - whether path A alone can backfill in acceptable time, or path B ships.

**Exit:** a `benchmark/README.md` "Scale" section with the measurements and the three decisions
recorded, plus the measurements the future limits will be based on.

---

## R — vespa-relay integration [R]

Each step is a PR in vespa-relay. They follow that repo's conventions:

- short KDoc, with history in `docs/decisions/`;
- a configured component is never silently inert;
- `ModuleBoundariesTest`;
- the store bump runs the store's integration gate.

| Step | Work |
|---|---|
| **R1** | **Dependencies.** Add `neo4j-eventstore` (pinned) to `libs.versions.toml`. `force` the vespa-eventstore store pin **and** quartz across all modules, so both stores share one `NostrSemanticsStore` (JitPack hashes have no order; see the relay's trap list). |
| **R2** | **Config** (`:common`). `openGraphStore()` reads `GRAPH_MIRROR` (default off), `NEO4J_URL`, `NEO4J_USER`, `NEO4J_PASSWORD`, `NEO4J_DATABASE`, `GRAPH_BODIES`, `GRAPH_MIRROR_QUEUE`, `GRAPH_RECONCILE_RECENT_SECONDS` and `GRAPH_RECONCILE_FULL_SECONDS`. Document them in `docs/configuration.md` and `.env.example`. `relay =` must be the same `RELAY_URL` given to Vespa, for NIP-62 scope. Refuse to boot when `GRAPH_MIRROR=on` and Neo4j is unreachable after a bounded retry. |
| **R3** | **Mirror wiring.** Wrap the `VespaEventStore` in `StoreMirror` in **both** `RelayMain` and `SyncMain`. Fix the three sites that downcast `as? VespaEventStore` (`liveGatesOf`, `RelayDiscovery`, `SyncEngine`) to take the Vespa store explicitly rather than by cast. With the mirror off, the wrapper is not installed at all, so the behaviour is byte-identical. |
| **R4** | **Reconciler job.** `relay/…/maintenance/GraphReconcile.kt` runs in the relay process only, like `ExpirationSweeper`. It has a cursor file like `FtsReindex`, and runs the dirty → recent → full cadence. |
| **R5** | **Compose.** Add a `neo4j` service: `neo4j:2026.09-community`, profile `graph`, loopback-only `7474`/`7687`, a `neo4j_data` volume, `NEO4J_AUTH` from env, page-cache and heap sized by P7, `mem_limit`, and a `cypher-shell 'RETURN 1'` healthcheck. Apply the §8.2.2 server hardening as env: `NEO4J_dbms_security_procedures_allowlist`, `NEO4J_dbms_security_allow__csv__import__from__file__urls=false`, and no plugins. No timeout or memory-cap settings in v1. The Neo4j browser (7474) is never published beyond loopback. `relay`/`sync` `depends_on` it only under the profile. Keep the existing memory-budget warning honest. |
| **R6** | **API and health.** Add `POST /graph` (Traversal JSON, no limits in v1) and `GET /graph/explain/{eventId}` (edges of one event). Add **`POST /graph/cypher`** (`{query, params}`), gated by `GRAPH_CYPHER=off\|admin\|auth\|public` (default `off`). `admin` reuses `AdminGate` / `Nip98AdminGate` against `RELAY_ADMIN_PUBKEYS`; `auth` accepts any valid NIP-98 signature. There are no rate limits or load shedding in v1. Every call is audited to `.audit/graph-cypher/`. Add `GET /graph/schema`. Add a `graph` block to `/stats.json` / pulse (§9 health surface) and a web status card. Traversal results hydrate from **Vespa** by id when `GRAPH_BODIES=skeleton`. |
| **R7** | **`GraphMirrorIT`** (Vespa + Neo4j containers). Drive the relay's ingest with injected drops and a Neo4j pause, assert id-set equality after one reconcile, and assert that client `OK` latency is unaffected while Neo4j is paused. |
| **R8** | **Docs:** `docs/decisions/graph-mirror.md` (D1/D2, why accepted events and not index deltas, why Vespa is the authority), a runbook in `docs/operations.md` (backfill, reset, "reconcile says N extra"), and a line in AGENTS.md's traps: "the graph mirror is a replica; never write to Neo4j directly". |
| **R9** | **Rollout on staging.** (a) The mirror on with the API off (shadow). (b) Backfill via P7's chosen path. (c) A reconcile reporting 0 missing / 0 extra for 7 consecutive days while the queue never overflows. (d) The API on. (e) Announce the endpoint and add the traversal JSON to the NIP-11 doc's extension notes. |

**Exit:** staging serves `POST /graph` for T1–T12 within the P7 latency budget, and the mirror
has held 0-drift for a week.

---

## P8 — Later, each a separate decision

- **Trust-aware traversals (Q6).**
  - Give Neo4j a `ReputationIndex`: cells as `(:User)-[:SCORED {rank, followers}]->(:Service)`,
    or as User properties keyed by service.
  - `TrustProjection` can then decorate `Neo4jGraphIndex` unchanged.
  - Traversals gain `observer` / `minRank` step filters, reusing vespa-eventstore's lens
    resolution.
- **`:Relay` nodes** from `r` tags (NIP-65 read/write markers) and edge relay hints, for
  outbox-model and hint-graph queries.
- **Nostr-wire exposure** of traversals (Q4): a REQ extension, or a NIP-90 DVM request/response
  kind. It needs a NIP-shaped proposal first.
- **Profile properties on `:User`** (name, nip05 from kind 0) for display-free server-side
  filters. The `SKELETON` trade-off applies again.

---

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| The `EventIndex` port drifts toward Vespa-only needs, and D1 gets costly | Medium | V2 names neo4j-eventstore as a port implementer. `PortDecoratorsTest` fails loudly on a new member. The fallback in §2 (fork the policy) stays open. |
| Hub-node write contention (popular pubkeys, viral notes) limits ingest | Medium | Sorted-key batches, the driver's retries, and measurement in P7. Bulk import for backfill. Kind-typed relationships keep read expansions narrow regardless. |
| Disk/RAM at 212M events exceeds the single box | High | `SKELETON` bodies, a separate host, or a kind subset, decided by P7 numbers, not guesses. |
| The mirror silently stops (Neo4j down, queue overflowing) | Medium | Dirty-hour marking, reconciler cadence, and health gauges. A configured-but-not-draining mirror is a reported fault. |
| Raw Cypher used to write, read files or reach internal URLs (Community has no RBAC) | High without the guard | The layered `CypherGuard` (plan walk + read transaction + server allowlists + no plugins) with the `CypherGuardIT` hostile battery as a CI gate. Audience starts at `admin`. |
| Raw Cypher or traversals exhausting memory or CPU and starving the mirror (**accepted in v1: no limits**) | Medium | Deliberately deferred. Mitigations until then: the audience starts at `admin` (Q7), and the reconciler repairs any drift once the load passes. P7 and early production supply the numbers for timeouts, memory caps, concurrency and fanout/frontier caps. A separate analytics instance is the escape hatch if contention persists. |
| Bulk DM-metadata mining through Cypher | Medium | D3: the relay's mirror does not replicate kinds 4/1059/21059. |
| Quartz pin bumps change provider output | Medium | Appendix-driven golden tests turn red. `KindRegistryMigration` retypes edges. Pin history comments, as in vespa-eventstore. |
| Private-key leak via NIP-19 `nsec` in content | Certain without the rule | Store-side exclusion plus an invariant test (P2), and the upstream fix (P0). |
| GPL contamination by accidentally embedding Neo4j | Low | Driver-only dependency. A build check fails if any `org.neo4j:neo4j*` server artifact enters the runtime classpath. |
