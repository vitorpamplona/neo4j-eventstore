# neo4j-eventstore — specification

Status: **implemented** (2026-09-29). This repo holds the projection library; vespa-eventstore has
the observer hook; vespa-relay has the wiring. Where building it changed a decision, the text below
says so ("*Built:*"). It supersedes the event-store draft, which is in git history. The execution plan is [`plan.md`](plan.md).
**Schema 2.0** replaced the derivation of §4.2 and §5: relationship types are now the relations of
the link vocabulary ([`vocabulary.md`](vocabulary.md)), one mapper per Quartz event class says what
each reference means, and Quartz's hint providers are no longer read.

## Summary

A **graph projection** of the events [vespa-eventstore](https://github.com/NosFabrica/vespa-eventstore)
holds for [vespa-relay](https://github.com/NosFabrica/vespa-relay), queried **only through
Cypher**.

- Every event, user and address becomes a node.
- Every reference an event makes becomes a typed relationship. The references are named by
  Quartz's `EventHintProvider` / `PubKeyHintProvider` / `AddressHintProvider`, completed by a
  literal-tag fallback.
- One query can therefore join event kinds through their tags. Examples: "notes my follows
  zapped, by total sats", "the whole reply tree under a root", "who follows the people NIP-85
  provider S ranks above 80".

Neo4j is **not a relay and not an event store**:

- Nobody reads it with REQ.
- It keeps no event bodies. A Cypher result that returns an event is hydrated from Vespa by id.
- It holds only the kinds and tags worth traversing.

Vespa stays the single source of truth. Neo4j follows Vespa's physical changes and is repaired
against Vespa's id set.

Production today: **500M events, 62M pubkeys.**

---

## 1. Goals and non-goals

**Goals**

1. **An exact projection of Vespa's stored set, all kinds included** (§4.4). Every add and every removal Vespa makes appears in Neo4j:
   - supersession;
   - NIP-09 deletion;
   - NIP-62 vanish;
   - NIP-40 expiry;
   - sweeps and NIP-86 purges.

   A reconciler proves and repairs equality continuously (§7).
2. **As many references as Quartz can name**, from the hint providers plus a fallback for kinds
   Quartz does not type (§5). Each reference is typed by tag and source kind, so Cypher
   expansions on huge hub nodes stay narrow.
3. **Lean by design.** Nodes carry ids and the few properties queries filter on. Relationships
   carry properties only where their type does not already say it (§4). A small set of curated
   values is extracted from bodies: zap amounts, NIP-85 ranks, reaction symbols, names (§4.3).
4. **A read-only Cypher endpoint** as the only query surface (§8). It is guarded against writes,
   file and network access, and admin commands. The graph schema is a documented, versioned
   public contract.
5. **The engineering model of vespa-eventstore:**
   - modules;
   - a port with an in-memory executable spec;
   - source-reading guard tests;
   - a hermetic unit gate plus a testcontainers integration gate;
   - Quartz pinned by commit;
   - *why*-comments.

**Non-goals (v1)**

- **Serving Nostr.** There is no `IEventStore`, no REQ/COUNT/NIP-77, and no parity with Quartz's
  SQLite store.
- **Holding event bodies.** `content`, `sig` and raw tag arrays stay in Vespa.
- **Full-text search.** Search in Vespa, then pass the ids into Cypher as `$ids` (§8.7).
- **Query limits.** There are no timeouts, row, memory or concurrency caps, and no rate limits.
  They will be added from production measurements (plan P7). Only the security guards of §8.2
  apply.
- **Trust gating** of Cypher results. NIP-85 ranks are available as data (§4.3), but nothing is
  filtered by an observer.
- **Embedding Neo4j.** Neo4j Community is GPLv3. It is only ever a separate server reached over
  Bolt through the Apache-2.0 driver (§10).

---

## 2. Architecture and the three decisions

```
      vespa-relay                                                    Neo4j (separate host)
┌──────────────────────────────────────────────────────┐          ┌─────────────────────────┐
│ relay process ─ VespaEventStore.open(observers=[P])   │          │                         │
│ sync  process ─ VespaEventStore.open(observers=[P])   │          │   graph (lean)          │
│        │ every acked put / remove on the index        │          │                         │
│        ▼                                              │  Bolt    │                         │
│   P = GraphFeed ──queue──▶ GraphProjector ────────────┼─────────▶│  writes                 │
│                               ▲                       │          │                         │
│   MirrorReconciler ───────────┘ (Vespa ids vs Neo4j)  │─────────▶│  id-window snapshots    │
│                                                       │          │                         │
│   POST /graph/cypher ─ CypherGuard ───────────────────┼─────────▶│  READ transactions      │
│        └─ hydrate :Event rows from Vespa by id        │          │                         │
└──────────────────────────────────────────────────────┘          └─────────────────────────┘
```

**D1 — A projection, not a store.** Vespa already decides what is stored:
- dedup;
- the NIP-01 tiebreak;
- same-owner NIP-09;
- NIP-62 scope;
- expiry;
- bans.

Re-deciding any of it in Neo4j would only create a second, divergent opinion. Neo4j therefore
runs **no Nostr policy**. It applies Vespa's decisions, and holds a subset of each event's data.
This removes the whole `IEventStore` layer: filter compilation, counts, negentropy serving and
parity. What is left is the part that is actually new: derivation, projection, reconciliation
and Cypher.

**D2 — Changes come from vespa-eventstore's index seam.** Inside vespa-eventstore, **every
physical mutation passes through the `EventIndex` port**:
- inserts;
- supersession removals;
- kind-5 targets;
- vanish sweeps;
- expiry;
- orphan-score sweeps;
- NIP-86 purges;
- `deleteMissing`.

`TrustProjection` already relies on this to keep its reputation documents exact "with zero
deletion-specific code". We add a small, generic **observer hook** at that seam (§6.1, a
vespa-eventstore change). vespa-relay passes a `GraphFeed` observer into `VespaEventStore.open()`
in both of its writer processes, relay and sync.

- **Why the seam and not the relay's write call sites:** the relay has at least seven write
  sites. None of them sees the removals Vespa performs internally.
- **Why a hook and not a wrapper around `IEventStore`:** a wrapper sees a kind-5 arrive but not
  what it erased. It would also break the relay's `as? VespaEventStore` casts.
- *Fallback, if the hook is refused:* §6.4.

**D3 — Every kind is projected, and a tag policy decides which non-reference tags become
nodes** (§4.4).
- All event kinds Vespa holds are kept, DMs and gift wraps included.
- Single-letter tags that are not references become `:Tag` nodes only for an allowlist of names:
  `t`, for example, but not `x` file hashes, which would add one useless node per event.
- A kind exclude list exists as an operator knob, **empty by default**.

**Modules** (repo `neo4j-eventstore`; a rename is open question Q5):

| Module | Packages (layer order, enforced by `ModuleBoundariesTest`) | Depends on |
|---|---|---|
| `:engine` | `schema/` (labels, keys, policy) → `vocab/` (relations, typed props, `LinkBuilder`) → `kinds/` (one mapper per Quartz class, `KindLinks`) → `derive/` (`EdgeDeriver`, `Secrets`, `Extractors`) → root port (`GraphIndex`) → `metrics/` → `memory/` (`InMemoryGraphIndex`, **the executable spec**) + `client/` (`Neo4jGraphIndex`) | Quartz, neo4j-java-driver |
| `:projection` | `feed/` (`GraphFeed`, `GraphProjector`, dirty windows) · `reconcile/` (`MirrorReconciler`) · `cypher/` (`CypherGuard`, `CypherService`, `ResultEncoder`, `Hydrator` port) · root: the facade `GraphProjection.open()` | `:engine`, Quartz |
| `:benchmark` | Bulk loader (Vespa dump → CSV → `neo4j-admin import`), ITs, probes; unpublished | both |

**The library depends on Quartz only, not on vespa-eventstore.**
- The observer interface it implements lives in vespa-eventstore's facade, and speaks Quartz
  types.
- The reconciler and the hydrator talk to Vespa through a two-method port (§7.1), which
  vespa-relay implements with `VespaEventStore`.

**The port:**

```kotlin
interface GraphIndex : AutoCloseable {
    /** Project these stored events (idempotent; applies the supersession rule of §6.2). */
    suspend fun apply(events: List<Event>)
    /** Unproject these ids (idempotent; the stub rule of §4.1). */
    suspend fun unapply(ids: List<String>)
    /** (created_at, id) of every :Stored event in [since, until], ascending — the reconciler's side. */
    suspend fun visitIds(since: Long, until: Long, onPage: suspend (List<IdAndTime>) -> Boolean)
    /** What one event contributed, as derived — debugging and the derivation parity test. */
    suspend fun edgesOf(id: String): List<EdgeView>
}
```

The two implementations are `InMemoryGraphIndex` (the spec) and `Neo4jGraphIndex`. Cypher is not
part of the port: it is Neo4j-only, and lives in `:projection/cypher/` over the driver.

---

## 3. Scale and capacity

Production holds **500M events and 62M pubkeys.** The sizing below is an estimate to be replaced
by the plan's P7 measurement on a staging slice.

| Item | Estimate | Basis |
|---|---|---|
| `:Event` nodes | 500M | Every kind (§4.4), plus stubs for referenced-but-absent events |
| `:User` nodes | 62M | One per pubkey that authored or was referenced |
| `:Address` / `:Tag` nodes | tens of millions | Addressables plus referenced replaceables only (§4.1); allowlisted tag names only |
| Relationships | **3–6B** | 500M `AUTHOR` edges, plus references (~1–2B), plus current follow lists (≈10–20M lists × a few hundred `FOLLOW` each) |
| Store on disk | **~300–450 GB** | Record ("aligned") format: 34 B per relationship record and 15 B per node record. Id strings (64-hex) and their unique index are a large share: ~100 GB for events alone. |
| With bodies (not done) | roughly ×2 | The reason bodies stay in Vespa |

Consequences:

- **A dedicated Neo4j host.** The graph cannot share vespa-relay's box (Vespa alone is budgeted
  at 34 GB there). Query speed depends on how much of the relationship store, node store and id
  index fit in the page cache. The current guess is 256–512 GB RAM with NVMe. P7 turns this
  into a number (Q3).
- **Neo4j Community capacity.** Community uses the record format, whose documented ceilings
  (~34B nodes, ~34B relationships) are far above 6B. The block format is Enterprise-only. Both
  facts are to be re-verified against the 2026.09 docs in P1.
- **Initial load must be offline.** At this size, only `neo4j-admin database import full`
  (available in Community) loads in hours rather than weeks (§7.3).
- **The id encoding is the biggest single lever.** 64-character hex strings are readable in
  Cypher but cost ~2× a compact encoding. v1 keeps the strings, because the schema is a public
  contract (§8.6). P7 measures the alternative (Q6).

---

## 4. Graph data model

### 4.1 Nodes

| Label | Key (unique constraint) | Properties | Exists when |
|---|---|---|---|
| `:Event:Stored` | `id` | `kind`, `created_at`, `d` (addressables), `expires_at` (NIP-40), curated values (§4.3), `derived` (the derivation stamp, §7.2) | Vespa holds the event |
| `:Event` (stub) | `id` | none | Something references an id we do not hold (never seen, excluded, or removed) |
| `:User` | `pubkey` | none: names stay on the kind 0 that states them (§4.3) | It authored, or was referenced |
| `:Address` | `id` = `kind:pubkey:d` (Quartz `AddressSerializer` form) | `kind`, `pubkey`, `d` | Any **addressable** event (30000–39999), or a reference to any address, including a replaceable one such as `10002:<pk>:` |
| `:Tag` | `key` = `name:value` | `name`, `value` | A value a kind's mapper links that is not an event, user or address: a hashtag, a URL, an external id, a group id (§5) |
| `:Meta` | singleton | `schema_version`, `policy_hash`, `derivation_version`, `derived` | Written by `SchemaInstaller` |

- **The author is an edge, not a property.** An event's author is its `AUTHOR` edge. Dropping a
  64-character `pubkey` string from 500M nodes saves roughly 40–50 GB. The edge is also the
  efficient way to filter by author: start from the `:User`. This is a deliberate trade against
  convenience (Q6).
- **Replaceable and addressable events both have an `:Address`** (`3:<pk>:` for a follow list,
  `30023:<pk>:<d>` for an article), reached through their `ADDRESS` edge. It is the NIP-01 slot:
  a user's current follow list is `(:Address {id: '3:' + $pk + ':'})<-[:ADDRESS]-(list:Stored)`,
  an index seek, and there is exactly one. *Built (2.0):* 1.x gave replaceables no address and
  found the slot through the kind-typed author edge, which 2.0 no longer has.
- **Stubs keep references alive.** A reply to a note we never saw still points at
  `(:Event {id})`. When the note arrives, the stub gains `:Stored`, and the reply's edge is
  already in place.
- **Unapply** (§6.2):
  1. delete the event's outgoing relationships and its properties;
  2. drop `:Stored`;
  3. delete the node if nothing points at it any more, otherwise keep it as a stub.

  Every node the unapply leaves without relationships (`:User`, `:Address`, `:Tag`, stubs) is
  deleted in the same transaction. *Built:* there is no periodic orphan sweep, because none
  leaks: each drop LOCKS the node, then tests `NOT EXISTS { … }`, so a concurrent writer's new
  edge is either seen (the node stays) or waits for the delete to commit and re-creates the node.

**Indexes:**
- the four uniqueness constraints;
- range indexes on `:Stored(created_at)` (the reconciler's windows) and `:Stored(kind)`;
- `:Stored(expires_at)`;
- `:Stored(nip05)` (kind-0 names), `:Address(kind)`;
- relationship indexes on `report` and `report_raw` of `REPORTED_USER`, `REPORTED` and
  `REPORTED_AUTHOR`.

### 4.2 Relationships

Every relationship **originates at an `:Event:Stored`**, except an address's `AUTHOR`.
Unapplying an event therefore removes exactly its own contribution. No removal style needs
graph-specific code.

**The type is a relation of the link vocabulary** ([`vocabulary.md`](vocabulary.md), the
catalogue in [`schema.md`](schema.md)): what the target IS to the event that states it —
`AUTHOR`, `ADDRESS`, `ROOT`, `PARENT`, `REACTED`, `REACTED_AUTHOR`, `FOLLOW`, `REPORTED_USER`,
`ZAP_SENDER`, `SUBJECT`, … One relation per role across kinds: `PARENT` is a note reply's parent,
a NIP-22 comment's parent item and a git reply's; the source node's `kind` tells them apart.

- **Plain UPPER_SNAKE identifiers**, the Cypher convention; no backticks.
- **Dense nodes stay cheap.** Neo4j groups a node's relationships by type and direction, so
  `(u)<-[:FOLLOW]-()` walks only follow lists (FOLLOW comes from kind 3 alone; other follow-like
  lists are `SUBSCRIBED`), and `COUNT { (u)<-[:FOLLOW]-() }` is an **O(1)** follower count. The
  vocabulary splits a relation wherever queries separate its meanings on one target type
  (`REPORTED_USER` vs `REPORTED_AUTHOR`), for the same reason.
- *Built (2.0):* 1.x named types `<tag>_<kind>` (`p_3`, `e_1111`) with a kind registry and an
  `_other` bucket; the names were complete without curation but pushed each kind's tag semantics
  onto every query author. The kind is now only on the source node.

**Relationship properties** are the link's typed props plus `via`:

| Property | On | Meaning |
|---|---|---|
| `via` | every link a tag or the content states | the tag name (`e`, `p`, `30382:rank`, …) or `content` for a `nostr:` URI (NIP-27). One target reached two ways is two relationships. |
| the relation's props | e.g. `REPORTED*` (`report`, `report_raw`), `SUBJECT` (`rank`, `followers`, every NIP-85 metric), `ZAPPED` / `ZAP_RECIPIENT` (`msats`), `MEMBER` (`roles`) | Declared per relation (`vocab/props`); every key and its type is in `PropsColumns`. |

**Not kept:**
- tag positions;
- relay hints;
- NIP-10 markers verbatim (they become `ROOT` / `PARENT` / `MENTION`);
- a per-edge timestamp. A time filter reads the source node's `created_at`.

### 4.3 Curated properties

Bodies stay in Vespa, but a few values are what graph queries actually filter or rank on.
Extracting them keeps whole classes of query inside Cypher. Values that qualify ONE reference (a
report's category, an assertion's rank, a zap's amount) ride that edge as the relation's props
(§4.2); the values below live on nodes, each a small `Extractors` entry built on a Quartz helper:

| Kind | Where | Property | Quartz source |
|---|---|---|---|
| 0 | the profile's `:Event` | `name`, `display_name`, `nip05` (each ≤ 256 bytes) | kind-0 metadata parse. Never copied onto the `:User`: the current profile is one hop away through its `0:<pk>:` address, and a copy would need clearing and restoring on every removal. *Built (2.0):* 1.x also copied them onto the `:User`. |
| 7 | the reaction's `:Event` | `content` (≤ 32 bytes: `+`, `-`, an emoji or a `:shortcode:`) | `ReactionEvent.content` |
| 9735 | the receipt's `:Event` | `msats` | `ZapReceiptEvent.amount()` |
| 9734 / 9321 / 8333 / 9736 | the event | `msats` where the kind states an amount |  |
| 30023, 30311, 34550 | the event | `title` (≤ 256 bytes) | the `title` tag |

Adding an extractor is an additive schema change. *Built:* it reaches events already held
through re-derivation (§7.2): every `:Stored` node carries the derivation stamp it was written
with, and bumping `Derivation.VERSION` with the change makes the reconciler rewrite each held
event in place, paced by the full sweep. The same holds for a mapper fix or a Quartz bump that
changes a parse.

### 4.4 Kind policy (`schema/GraphPolicy`)

The policy is configuration, not schema. Its hash is stored in `:Meta`. A changed policy makes
the reconciler converge the graph to the new filter.

- **Kinds: all of them.** Every kind Vespa holds is projected. Ephemeral kinds never reach
  Vespa.
  - An exclude list exists as an operator knob. It is **empty by default**, and nothing in the
    design depends on using it.
  - If it is ever used, the reconciler unapplies a newly excluded kind and copies a newly
    included one from Vespa.
- **Tag values are not configuration.** Which values become `:Tag` nodes is each kind's mapper's
  decision (`t` is a hashtag on a note but an auth verb on 24242). The policy only bounds them:
  a value over 256 bytes is never a node. *Built (2.0):* 1.x had a global allowlist of letters.

### 4.5 Not modelled in v1

- `:Relay` nodes (NIP-65 read/write, relay hints).
- NIP-51 private (encrypted) members: the projection holds no keys.
- Trust-gated views: NIP-85 values are data (§4.3), not a filter.

---

## 5. Deriving the graph from an event (`kinds/`, `derive/`)

`EdgeDeriver.derive(event: Event): GraphDoc` is **pure**, with no I/O. It is the same function in
the live projector, in `InMemoryGraphIndex`, and in the bulk CSV writer, so the three can never
disagree about *what* the graph is.

The input is re-typed through Quartz's `EventFactory` (a store hands back plain `Event`s), because
the mappers are registered by Quartz class.

### 5.1 Links: what the references mean (`kinds/`)

`KindLinks.of(event)` states the event's links, in the vocabulary of [`vocabulary.md`](vocabulary.md):
1. `AUTHOR` → its pubkey; `ADDRESS` → its own address, for replaceable (`kind:pubkey:`) and
   addressable (`kind:pubkey:d`, the first `d`) kinds;
2. the tags any kind may carry: a NIP-89 `client` (`CLIENT`), NIP-57 zap splits (`ZAP_SPLIT` with
   the weight), a NIP-30 emoji's set (`EMOJI_SET`);
3. the class's **mapper**: one per Quartz event class, reading the tags only through Quartz's Tag
   parsers and accessors (small local parsers where Quartz has none), e.g. a reaction's LAST
   `e`/`a` is `REACTED` and its last `p` `REACTED_AUTHOR`, earlier ones `MENTION`.

There is **no fallback**. A kind the pinned Quartz does not type states only 1 and 2; a class
without a mapper fails `KindMappersCoverageTest`. The per-class review showed why guessing from a
value's shape is unsafe: a 64-hex `e` in a chess start event is a board hash, and `t` is an auth
verb in 24242.

`LinkBuilder` validates every target (64-hex ids and keys, lowercased; `kind:<64-hex>:d`
addresses in key form, a `d` over 1024 bytes replaced by its hash; non-blank values) and drops
exact duplicates.

### 5.2 Edges: what is stored (`derive/`)

Each link becomes an edge of type `relation.name`, with the props in store form plus `via`
(numbers widened to `Long` / `Double`, as Neo4j returns them). The deriver adds the rules about
storage, not meaning:

| Rule | Why |
|---|---|
| **The nsec rule**: no target and no prop value may carry a private key, as the hex of an `nsec1…` pasted in the content or as bech32 in any value | Quartz's `ListEntityExt.pubKeys()` has mapped an `NSec` to its hex; a tag value can carry one verbatim. A security requirement, covered by an invariant test. |
| No self-links | An event naming its own id adds nothing and would pin its own stub. |
| A `:Tag` value over 256 bytes is dropped | The key sits behind a uniqueness constraint. |
| The slot is the `ADDRESS` edge | Supersession (§6.2) looks the incumbent up through it. |

Curated node values (§4.3) and `expires_at` come from `Extractors`.

### 5.3 Worked example

A NIP-10 reply (kind 1, by `A`) with these tags:
- `["e",R,"wss://a","root"]`
- `["e",P,"","reply"]`
- `["p",X]`
- `["t","nostr"]`
- `["x","<sha256>"]`

and content `"… nostr:npub1<Y> … nostr:nsec1<Z> …"` projects to:

```
(ev:Event:Stored {id, kind:1, created_at})-[:AUTHOR]->(:User {pubkey:A})
(ev)-[:ROOT {via:"e"}]->(:Event {id:R})
(ev)-[:PARENT {via:"e"}]->(:Event {id:P})
(ev)-[:MENTION {via:"p"}]->(:User {pubkey:X})   // PARENT_AUTHOR if X were P's author
(ev)-[:HASHTAG {via:"t"}]->(:Tag {key:"t:nostr"})
(ev)-[:MENTION {via:"content"}]->(:User {pubkey:Y})
// x: a kind 1 does not link its file hashes. nsec1<Z>: never written.
```

---

## 6. Keeping the projection exact

### 6.1 The observer hook (a vespa-eventstore change)

This is added to vespa-eventstore's facade package and threaded through `VespaEventStore.open()`:

```kotlin
/** Told about every acked physical write to the event index. MUST NOT block or throw. */
interface IndexObserver {
    fun onPut(events: List<Event>)      // after the put is acked (visible to search)
    fun onRemove(ids: List<String>)     // after the remove is acked
}
fun open(..., observers: List<IndexObserver> = emptyList()): VespaEventStore
```

- *Built:* vespa-eventstore `bebbf90493`, with `IndexObserver` and `ObservedEventIndex` in
  `engine.observe`. `:engine` cannot import the facade, and `:store` exposes `:engine` as `api`.
  On the default engine path the decorator runs supersession through itself, so the replaced
  version's removal IS reported. Only the atomic address-keyed path hides it.
- **Placement.** A decorator `ObservedEventIndex` sits directly over
  `MeteredEventIndex(VespaEventIndex)`, **below** `TrustProjection`. Every event mutation any
  layer makes passes through it: the store's own, and the trust layer's supersession removals.
  - It forwards every port member, so it must be added to that repo's `PortDecoratorsTest`
    list.
  - It reports only after the inner call returns.
  - Callback exceptions are caught and counted, never propagated into the write path.
- **What it cannot see, and why that is fine.** If the address-keyed engine path is enabled
  (`VESPA_ADDRESS_KEYED=1`), supersession happens atomically inside Vespa, and the old version's
  removal is never reported. The projector does not depend on it, because it applies the
  NIP-01 supersession rule itself (§6.2). That rule would be needed anyway for out-of-order
  delivery.
- **Read-only walks.** The same change exposes two read-only walks on `EngineReads`, needed by
  the reconciler and the bulk loader:
  - `visitIds(query, withKind = true)`
  - `visitDocsPage(query, resumeFrom, maxDocs)`

  `DocRef` gains an optional `kind`.

### 6.2 The projector (`feed/GraphProjector`)

`GraphFeed` implements `IndexObserver`:
- it drops any kind on the exclude list (§4.4; empty by default);
- it enqueues into a bounded in-memory queue;
- on overflow it drops the entry and marks that entry's `created_at` hour **dirty** for the
  reconciler. It never blocks Vespa.

A single consumer per process drains the queue in batches into `GraphIndex.apply` / `unapply`.

*Built:* each EVENT (and each removal) is its own managed write transaction, not each batch.
Measured on 2026.09, a statement that reads a node deleted by an earlier statement in the same
transaction intermittently fails ("Node … has been deleted in this transaction") instead of
skipping it. For the same reason, an apply that displaces an incumbent keeps the nodes the new
version re-references. Edges use dynamic relationship types
(`CREATE (s)-[:$(row.type)]->(t)`), and the driver retries on deadlock. Each apply first WRITES
to the event node and its slot anchor (its own `:Address`), taking their locks, so two writer
processes cannot both win a slot.

**`apply(e)`:**
1. Already `:Stored` → no-op (idempotent).
2. If `e` is **replaceable or addressable**, the incumbent is the other `ADDRESS` source on its
   own `:Address` (`kind:pubkey:` or `kind:pubkey:d`).
   - If the incumbent wins under NIP-01 (higher `created_at`, then the lower id), skip `e`: it
     is a stale delivery.
   - Otherwise, unapply the incumbent in the same transaction.
   - *Built:* after a bulk load one slot can hold several versions until the reconciler removes
     the losers. They are read winner first (`created_at DESC, id ASC`); `e` is compared with
     the winner and, if it wins, unapplies them all.
3. If `e`'s id was **unapplied within the last hour** (the recent-removal table, below), skip
   it. It is a late put racing its own removal.
4. Write the node (promoting a stub if one exists), its edges (its `ADDRESS` among them), each
   new `:Address`'s `AUTHOR` edge to its pubkey, and its curated values (on its own node only).

**`unapply(id)`:**
- apply the stub rule (§4.1);
- record `(id, now)` in the recent-removal table, a `:Removed {id, at}` node with a TTL sweep.

**Ordering.** The two writer processes feed Neo4j independently, so deliveries can interleave in
any order. The rules above make the final state order-independent for every case except one
residual:
- supersession is decided locally;
- duplicates are no-ops;
- late puts are fenced by the recent-removal table.

The residual is a remove that arrives more than an hour before its own put. The reconciler
repairs that as an "extra".

### 6.3 Latency and failure

- **Vespa's write latency is unaffected.** The observer only enqueues.
- **If Neo4j is slow or down,** the queue fills, entries are dropped, and hours are marked
  dirty.
- **If a process crashes** between the Vespa ack and the apply, the reconciler repairs it.
- **If a source write throws** (a bulk write that timed out after some chunks landed), the
  observer's `onUncertain` marks the batch dirty instead of reporting it either way.
- **If the graph refuses one event** (a non-transient client error), that event alone is
  isolated (`ApplyOutcome.failed`) and marked dirty; the rest of its batch applies.
- **On shutdown** the feed drains for up to 10 s; whatever is left is marked dirty and saved.
  *Built:* the bound holds while Neo4j is down. The apply loop checks cancellation between
  events, and a batch still blocked in the driver after a 1 s grace is abandoned and marked
  dirty.
- The queue is bounded in events and ids (default 100k), not in calls, because one sync call
  can carry thousands of events.

There is no durable outbox in v1. The reconciler *is* the durability mechanism, and it is needed
anyway.

### 6.4 Fallback if the hook is not accepted

Forward the relay's **accepted events** and filter deletes from an `IEventStore` decorator, and
re-apply NIP-09 / NIP-62 in the projector. The graph makes these cheap:
- "is this deleted?" is `EXISTS { (:Stored {kind:5})-[:DELETED]->(target) }` with the same
  author;
- vanish is an author-scoped unapply.

This costs a second, small policy implementation, plus reliance on the reconciler for Vespa-only
removals (sweeps, purges). It also requires fixing the relay's `as? VespaEventStore` casts.

---

## 7. Reconciliation and backfill (`reconcile/`)

### 7.1 The Vespa port

```kotlin
interface SourceOfTruth {                  // vespa-relay implements it over VespaEventStore.engine
    suspend fun visitIds(since: Long, until: Long, onPage: suspend (List<IdAndTime>) -> Boolean)
    suspend fun visitRefs(since: Long, until: Long, onPage: suspend (List<SourceRef>) -> Boolean) // default: kind unknown
    suspend fun fetch(ids: List<String>): List<Event>
}
```

*Built:* `visitRefs` lists each id with its kind, where the source can say it without reading
bodies. Only a policy that excludes kinds uses it: an excluded kind's id is then left out by its
kind instead of by fetching every body in the window. Its default answers `visitIds` with the
kind unknown, so an implementation written before it keeps working.

The implementation reads un-lensed and un-gated, via `EngineReads` (§6.1). The same `fetch`
serves Cypher hydration (§8.3).

### 7.2 `MirrorReconciler`

For a `created_at` window, the reconciler streams both sides in `(created_at, id)` order:
- Vespa, filtered to policy-included kinds;
- Neo4j `:Stored`.

*Built:* the graph side is listed FIRST. Listed after the source, an event the source acked and
the feed applied between the two listings looked extra and was unapplied. In this order that race
resolves harmlessly: the event looks missing and is applied a second time, which is a no-op.
Both listings are capped at the window size, so a window splits when EITHER side is too big.

It merge-diffs them:
- **extra** in Neo4j → `unapply`, first;
- **missing** in Neo4j → `fetch` (chunks of 500) → `apply` **authoritatively**.

*Built:* an authoritative apply carries the second the source was read at (just before the
`fetch`). It bypasses a fence stamped BEFORE that read, because the source said the event was
held after the removal. A fence stamped at or after the read wins: the feed removed the event
after the fetch saw it, and applying would resurrect it. The apply still respects NIP-01 in its
slot. An incumbent that out-ranks it is kept and reported (`ApplyOutcome.outranked`), and the
reconciler asks the source about it: if the source no longer holds it, it is unapplied as an
extra and the event applied again. If the source still holds it, the feed superseded the event
after the fetch, and evicting the newer version would be wrong. Without this, a stale version of
a slot whose winner sat in another window was left out forever. It covers the two residuals no
local rule can see:
- a remove delivered more than a fence window before its own put;
- with unreported (atomic) supersession, a stale version delivered after its successor was
  itself removed, so the slot looks empty.

*Built:* **re-derivation.** Every `:Stored` node carries `derived`, the stamp of the derivation
that wrote it (`Derivation.stamp`: the schema major, `Derivation.VERSION` and the policy hash).
The graph listing returns it, and a held event whose stamp is not the running build's is fetched
and rewritten in place (`GraphIndex.rederive`: one transaction, old edges unapplied while
keeping every node the new derivation references, no fence and no slot contest). Re-derived
events count against the sweep's budget. A leaf may use at least half the budget for them and
stops on a whole second, so the next tick resumes there. `SchemaInstaller` records the stamp in
`:Meta` and REFUSES a graph whose `schema_version` has another major: a major renames types,
which re-deriving cannot migrate, so it is a rebuild.

Windows are sized to about 250k ids. A 500M-id snapshot cannot be materialized; staging measured
~5.3 GiB for 43.7M ids.

Cadence:
1. Dirty hours first.
2. A rolling **recent pass**: the last 2 h, every 5 min.
3. A continuous **full sweep** over the whole corpus, resumable from a cursor file. Its period
   (a week or better) is measured in P7.

   *Built:* each tick spends a budget of source ids (250k), not a fixed span of time. An empty
   window widens the next ×4, up to ten years. A widened window that turns out to hold data
   (most of the corpus, say) is reconciled leaf by leaf, oldest first, only until the budget is
   spent, and the cursor is saved where it stopped (`reconcileUpTo`). Each wrap also reconciles
   what the cursor never visits: `created_at` below the sweep start, and everything after now.
   The corpus has notes dated 2100, and the recent pass reaches only 15 minutes past now.
4. The fence sweep: `:Removed` entries older than twice the fence window are deleted.

Work drained from the dirty tracker is put back if the tick fails before reaching it. A process
that only feeds the graph (vespa-relay's sync) runs `tick(dirtyOnly = true)`, repairing its own
drops, since its tracker lives in its own memory. The tracker is saved to a file on close and
loaded on open, so a restart keeps what is owed.

*Built:* nothing a stage could not do is lost, and nothing poison blocks the rest:
- an event the graph refuses in any stage marks its hour dirty with exponential backoff (5 min,
  doubling per consecutive failure, at most a day);
- a dirty hour that throws is put back with the same backoff, and the tick goes on with the
  other hours and stages. Three in a row mean the graph or the source is down, so the rest go
  back untouched;
- removal ids that do not fit the tracker are kept up to its bound. The overflow flag clears once
  a whole sweep pass that began after the last overflow has wrapped.
- `GraphProjection.close()` stops the loop `ReconcileLoop.start` launched before saving the
  tracker (a cancelled tick puts back what it drained). An embedder that drives `tick()` itself
  stops it first.

Races are benign:
- a missing event still in the live queue is applied twice, and the second apply is a no-op;
- an extra removed ahead of its own delivery is fenced by the recent-removal table.

After a policy change, the full sweep converges the graph to the new filter.

**Correctness, stated plainly:**
- Neo4j receives only what Vespa acked: puts and removes.
- It applies them order-independently (§6.2).
- Anything lost (overflow, crash) or never reported (Vespa-internal supersession) is caught by a
  reconciler that treats Vespa's id set as the truth.

Divergence is therefore bounded by the recent-pass cadence for live traffic, and by the
full-sweep period for older data.

### 7.3 Backfill: bulk import

At 500M events, online `apply` is the wrong tool for the initial load. The plan's `graphDump`
loader does this instead:

1. **Start the feed first** (with the graph empty, it only marks hours dirty), and note `T0`.
2. **Dump Vespa** with `EngineReads.visitDocsPage` (all kinds). Run each event
   through the **same `EdgeDeriver`**, and write node and relationship CSVs, one file per
   relationship type.
   - *Built:* the writer streams, with no in-memory dedup. `--skip-duplicate-nodes` keeps the
     FIRST occurrence, so file order puts held events before stubs and named users before bare
     ones.
   - Supersession duplicates are NOT sorted out. The dump spans time, so an old and a new
     version can both appear; the reconciler's catch-up removes the loser as an extra.
3. **Import** with `neo4j-admin database import full neo4j …`. The database name goes first,
   because `--relationships` swallows a trailing positional argument. Then run
   `BulkImport.finalize()`: `SchemaInstaller`, plus every address's `AUTHOR` edge, which a
   streaming writer cannot deduplicate.
4. **Catch up:** reconcile `[T0 − 1 day, now]` plus the dirty hours. Then let the full sweep run,
   which also removes anything the dump caught mid-change.

`BulkImportIT` asserts that the bulk path and the online path produce identical graphs for the
same corpus.

---

## 8. The Cypher endpoint (`cypher/`)

### 8.1 API

The library provides `CypherService`. vespa-relay exposes it as **`POST /graph/cypher`**, with
the body `{"query": "...", "params": {...}, "hydrate": true}`, and **`GET /graph/schema`**
(§8.6). Who may call it is configuration: `GRAPH_CYPHER=off|admin|auth|public` (Q4).

### 8.2 Guards (`CypherGuard`)

**Why it needs its own guards:** Neo4j **Community has no role-based access control.** The one
account the service connects with can write, create users, call any procedure, and read files
or URLs through `LOAD CSV`. Protection has to be layered around the query:

1. **Pre-flight `EXPLAIN`, then inspect the plan, not the text.** Nothing executes at this
   stage. The query is rejected unless `queryType()` is `READ_ONLY`, and also rejected if the
   plan contains any of:
   - `LoadCSV` (local files, and SSRF through `http://`);
   - a procedure or function outside a short allowlist (`db.labels`, `db.relationshipTypes`,
     `db.propertyKeys`, `db.schema.*`);
   - `Show*` / `Terminate*` operators. `SHOW TRANSACTIONS` would reveal other callers' queries.

   Inspecting the plan defeats comment, casing and unicode tricks.
2. **Execute in a read transaction** (`AccessMode.READ`, `executeRead`) on the data database.
3. **Server configuration**, asserted at boot (the service refuses to start if unsafe):
   - no plugin FUNCTIONS (`SHOW FUNCTIONS … WHERE NOT isBuiltIn`). A function runs inside any
     expression, where the procedure allowlist cannot see it. *Built:* the 2026.09 image ships
     `fleetManagement.*` procedures. They are unreachable past the guard's allowlist, so
     procedures are not what the boot check asserts;
   - `dbms.security.allow_csv_import_from_file_urls=false`, with no import directory.
4. **No resource limits in v1.** There are no timeouts, row, byte or memory caps, and no
   concurrency or rate limits. A heavy query runs to completion and can slow the projector. The
   reconciler repairs any drift once the load passes. Limits come from production data
   (plan P7).
5. **Audit.** Every call is logged: caller, query hash, parameter names, elapsed time, rows, and
   the outcome or rejection reason.

Parameters are passed natively. Nothing is string-interpolated.

### 8.3 Results and hydration

Rows are streamed as `{"columns":[…], "rows":[[…]], "elapsedMs":n}`. Graph values serialize by
label:

- `:Event:Stored` becomes the **full NIP-01 event**, fetched from Vespa by id in batched
  `SourceOfTruth.fetch` calls (`hydrate: true`, the default). With `hydrate: false`, it is the
  node's properties.
  - An event Vespa removed between the Cypher read and the fetch comes back as
    `{"id", "stored": false}`.
- A stub `:Event` is `{"id", "stored": false}`.
- `:User` is `{"pubkey"}`, `:Address` is `{"address", "kind", "pubkey", "d"}`, and
  `:Tag` is `{"name", "value"}`.
- A relationship is `{"type", "start", "end", …props}`, and a path is an alternating list.
- Integers outside ±2^53 are strings.

### 8.4 What callers see

Everything the projection holds, ungated by the relay's observer lens or trust floor. The
NIP-85 rank on `SUBJECT` edges lets a *query* apply a trust filter itself.

**DM metadata is included**, because every kind is projected (§4.4):
- kind-4 DMs carry sender and recipient in the clear;
- gift wraps (1059) carry their recipient `p`.

In the graph that becomes `AUTHOR` / `RECIPIENT` edges from kinds 4 and 1059, and a single query can turn them
into a "who messages whom" graph. The relay already serves the same events by REQ, so nothing
new is exposed, but bulk analysis becomes trivial. This is one more reason the Cypher audience
starts at `admin` (Q4). The kind exclude list (§4.4) is the lever if that ever needs to
change.

### 8.5 Hostile-query battery (`CypherGuardIT`)

Every case must be rejected before execution, or fail without side effects. The test asserts
the database is unchanged afterwards (counts, `:Meta`, users).

- **Writes:**
  - `CREATE` / `MERGE` / `SET` / `REMOVE` / `DELETE` / `DETACH DELETE`;
  - `FOREACH` writes;
  - `CALL {…} IN TRANSACTIONS`.
- **Schema and admin commands:**
  - index and constraint DDL;
  - `CREATE USER` / `ALTER USER` / `SHOW USERS`;
  - `SHOW TRANSACTIONS` / `TERMINATE TRANSACTIONS`;
  - `STOP DATABASE`;
  - `USE system …`.
- **File and network access:** `LOAD CSV` from `file:///etc/passwd` and from
  `http://169.254.169.254/…`.
- **Procedures:** `CALL dbms.*`; non-allowlisted procedures; `apoc.*`.
- **Evasion:** comments, mixed case, unicode escapes, multi-statement payloads.

### 8.6 The schema as a public contract

- **`docs/schema.md`** is the reference. It covers every label, type family, property, implied
  and stored role, and curated value, with the example queries of §8.7.
- **`GET /graph/schema`** serves the live view:
  - `schema_version`;
  - labels and relationship types with counts;
  - every relation of the vocabulary;
  - the policy.
- **Versioning.**
  - Additive changes (a new relation, a new props field, a newly mapped kind, a new extractor)
    bump the minor version.
  - Renames and removals bump the major version, and are announced with migration notes.

### 8.7 Example queries (the reference battery)

These are also `ReferenceQueriesIT`, run against a fixture graph with known answers.

```cypher
// T1 — follower count (O(1) from the dense-node group) and the followers
MATCH (u:User {pubkey: $pk})
RETURN COUNT { (u)<-[:FOLLOW]-() } AS followers;
MATCH (:User {pubkey: $pk})<-[:FOLLOW]-(:Stored)-[:AUTHOR]->(f:User) RETURN f;

// T2 — follows-of-follows I don't follow, ranked by how many of my follows follow them.
// A user's current follow list is their `3:<pk>:` address's one version: an index seek.
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(mine:Stored)-[:FOLLOW]->(f:User)
MATCH (:Address {id: '3:' + f.pubkey + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(fof:User)
WHERE fof.pubkey <> $me AND NOT EXISTS { (mine)-[:FOLLOW]->(fof) }
RETURN fof, count(DISTINCT f) AS via ORDER BY via DESC LIMIT 50;

// T3 — the whole NIP-10 thread under a root (every reply tags the root)
MATCH (root:Event {id: $id})<-[:ROOT]-(n:Stored)
RETURN n ORDER BY n.created_at;

// T3b — the reply TREE, any depth, following PARENT edges
MATCH (root:Event {id: $id}) ((p)<-[:PARENT]-(c:Stored))+ (leaf)
RETURN leaf;

// T5 — notes my follows zapped this week, by total sats
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(f:User)
MATCH (f)<-[:ZAP_SENDER]-(z:Stored)-[:ZAPPED]->(n:Stored {kind: 1})
WHERE z.created_at >= $since
RETURN n, sum(z.msats) AS msats, count(DISTINCT f) AS zappers ORDER BY msats DESC LIMIT 50;

// T9 — who NIP-85 provider S ranks >= 80
MATCH (:User {pubkey: $service})<-[:AUTHOR]-(:Stored {kind: 30382})-[a:SUBJECT]->(u:User)
WHERE a.rank >= 80
RETURN u, a.rank ORDER BY a.rank DESC;

// T11 — hashtags used alongside #bitcoin in the last day
MATCH (:Tag {key: 't:bitcoin'})<-[:HASHTAG]-(n:Stored)-[:HASHTAG]->(o:Tag)
WHERE n.created_at >= $since AND o.key <> 't:bitcoin'
RETURN o.value, count(*) AS uses ORDER BY uses DESC LIMIT 20;

// Hybrid — full-text search in Vespa first (ids from a NIP-50 REQ), then graph in Cypher
UNWIND $ids AS id
MATCH (n:Event:Stored {id: id})<-[:REACTED]-(:Stored)-[:AUTHOR]->(r:User)
RETURN n, count(DISTINCT r) AS reactors ORDER BY reactors DESC;
```

`docs/schema.md` carries the full set:
- T4: reactions by my follows;
- T6: articles quoted by my follows;
- T7: badge awarded-and-worn;
- T8: community approvals;
- T10: most-cited missing events (stubs);
- T12: reporters of X that I follow, user-wide reports only (`scope: 'user'`).
- T13 / T14: reports by category (dropping invented types) and by the type as written.

---

## 9. Operations and health

- **Gauges.** vespa-relay's `/stats.json` and pulse page report:
  - `graph.queue.{depth, dropped}`;
  - `graph.dirtyHours`;
  - `graph.lagSeconds` (the oldest queued entry);
  - `graph.apply.{events, edges, retries}`;
  - `graph.reconcile.{lastRecentPass, sweepCursor, sweepPeriod, missing, extra}`;
  - `graph.cypher.{calls, rejected, p50, p99}`;
  - `graph.schemaVersion`.

  A configured projection that is not draining is a reported fault, never silently inert
  (vespa-relay convention).
- **Neo4j host.** Community 2026.09 on its own machine, with the page cache sized by P7.
  - Bolt reachable only from the relay's network.
  - The browser port (7474) is not published publicly.
  - The §8.2 server settings are applied in its config.
  - Backups are offline `neo4j-admin database dump`, or simply a rebuild: the graph is fully
    re-derivable from Vespa, so its backup story is "re-import".
- **Reset.** Stop the feed, drop the database, bulk-import (§7.3), restart the feed. The
  reconciler closes the gap.

---

## 10. Licensing

| Component | License | Use |
|---|---|---|
| `org.neo4j.driver:neo4j-java-driver` 6.3.0 | Apache-2.0 (verified in `neo4j-java-driver-parent-6.3.0.pom`) | Linked |
| `org.testcontainers:neo4j` | MIT | Tests only |
| Neo4j Community Server 2026.x | **GPLv3** | Separate process over Bolt only. **Never embedded.** A build check fails if any `org.neo4j:neo4j*` server artifact reaches the runtime classpath. |
| `neo4j-admin` (bulk import) | Part of the server distribution | Run as a tool against the server's data directory, never linked |

---

## 11. Testing

| Gate | Needs | Asserts |
|---|---|---|
| `./gradlew build` (unit) | Nothing | **Derivation:** golden tests per Quartz package (`kinds/<Package>LinksTest`), `KindMappersCoverageTest` (every Quartz class has a mapper), `MapperCodeReadsTagParsersTest`, extractors, and invariants (every type is a vocabulary relation; no self-edges; **no `nsec` ever becomes a node or a property**). **Projector semantics on `InMemoryGraphIndex`:** random interleavings of two feeds' puts and removes, plus drops, converge to the source's state after one reconcile. The source is Quartz's in-memory SQLite `EventStore` fed the same events. **`CypherGuard`** plan-walk unit tests on captured plans. **`ModuleBoundariesTest`, `PortDecoratorsTest`.** |
| `spotlessCheck` | Nothing | ktlint plus the MIT header |
| `-Pintegration` | Docker (`neo4j:2026.09-community`) | **`ProjectionIT`:** the same corpus and interleavings through `Neo4jGraphIndex` give `edgesOf(id)` identical to `InMemoryGraphIndex` for every id. **`CypherGuardIT`** (§8.5). **`ReferenceQueriesIT`** (§8.7). **`BulkImportIT`:** bulk path = online path. |
| vespa-eventstore | Its own gates | `ObservedEventIndex` in `PortDecoratorsTest`. An IT: every mutation style (insert, supersession, kind 5, vanish, expiry, orphan sweep) reaches the observer, with exactly the removed ids. |
| vespa-relay `GraphProjectionIT` | Vespa + Neo4j | The relay's ingest with injected drops and a Neo4j pause → equal id sets after one reconcile, and client `OK` latency unaffected while Neo4j is paused. |

---

## 12. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Add the observer hook to vespa-eventstore (D2), or use the relay-side fallback (§6.4)? | **Hook.** It is exact, generic, and the pattern the store already uses internally. |
| Q2 | Kinds | **Decided: keep all kinds.** The exclude-list knob stays, empty. |
| Q3 | Neo4j host size | Decide from P7: page-cache fit vs query latency on the staging slice |
| Q4 | Cypher audience (`admin` / `auth` / `public`) | **`admin` until limits exist**, then widen |
| Q5 | Repo, group and package name. It is no longer an event store. | e.g. `nostr-graph` / `com.vitorpamplona.nostr.graph` |
| Q6 | Node economy vs convenience: keep 64-hex string ids, and no `pubkey` property on events? | Yes for v1 (readable, public contract). P7 measures compact ids. |

## 13. Later

- **Query limits**, from production measurements.
- **Trust-aware views** (observer lens over the `SUBJECT` ranks).
- **`:Relay` nodes.**
- **A bounded traversal DSL** for the Nostr wire (a REQ extension or a NIP-90 DVM).
- **More extractors**, as queries ask for them.
