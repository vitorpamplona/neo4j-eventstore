# neo4j-eventstore — specification

Status: **draft for review**, rewritten 2026-09-29. It supersedes the event-store draft; that
earlier design is in git history. The execution plan is [`plan.md`](plan.md), and every Quartz
class this spec derives edges from is catalogued in
[`appendix-providers.md`](appendix-providers.md).

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

1. **An exact projection of Vespa's stored set.** The projection is filtered by a kind policy
   (§4.4). Every add and every removal Vespa makes appears in Neo4j:
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

**D3 — A kind and tag policy decides what is worth holding** (§4.4).
- DM metadata (kinds 4, 1059, 21059) is excluded by default, because Cypher would make "who
  messages whom" trivial to mine.
- Bulky kinds with no references can be excluded, from measured per-kind counts.
- Single-letter tags that are not references become `:Tag` nodes only for an allowlist of names,
  e.g. `t`, but not `x` file hashes.

**Modules** (repo `neo4j-eventstore`; a rename is open question Q5):

| Module | Packages (layer order, enforced by `ModuleBoundariesTest`) | Depends on |
|---|---|---|
| `:engine` | `schema/` (labels, type names, kind registry, policy) → `derive/` (`EdgeDeriver`, `LinkRules`, `RoleTable`, `Extractors`) → root port (`GraphIndex`) → `metrics/` → `memory/` (`InMemoryGraphIndex`, **the executable spec**) + `client/` (`Neo4jGraphIndex`) | Quartz, neo4j-java-driver |
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
| `:Event` nodes | ≤ 500M | Minus excluded kinds (§4.4), plus stubs for referenced-but-absent events |
| `:User` nodes | 62M | One per pubkey that authored or was referenced |
| `:Address` / `:Tag` nodes | tens of millions | Addressables plus referenced replaceables only (§4.1); allowlisted tag names only |
| Relationships | **3–6B** | 500M `by_<k>` edges, plus references (~1–2B), plus current follow lists (≈10–20M lists × a few hundred `p_3` each) |
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
| `:Event:Stored` | `id` | `kind`, `created_at`, `d` (addressables), `expires_at` (NIP-40), curated values (§4.3) | Vespa holds the event and the kind policy admits it |
| `:Event` (stub) | `id` | none | Something references an id we do not hold (never seen, excluded, or removed) |
| `:User` | `pubkey` | curated values from kind 0 (§4.3) | It authored, or was referenced |
| `:Address` | `id` = `kind:pubkey:d` (Quartz `AddressSerializer` form) | `kind`, `pubkey`, `d` | Any **addressable** event (30000–39999), or a reference to any address, including a replaceable one such as `10002:<pk>:` |
| `:Tag` | `key` = `name:value` | `name`, `value` | A single-letter, non-reference tag whose name is allowlisted (§4.4) |
| `:Meta` | singleton | `schema_version`, `kind_registry_version`, `policy_hash`, `quartz_pin` | Written by `SchemaInstaller` |

- **The author is an edge, not a property.** An event's author is its `by_<k>` edge. Dropping a
  64-character `pubkey` string from 500M nodes saves roughly 40–50 GB. The edge is also the
  efficient way to filter by author: start from the `:User`. This is a deliberate trade against
  convenience (Q6).
- **Replaceable events (0, 3, 10000–19999) get no `:Address` node** unless something references
  their address. A user's current follow list is `(:User)<-[:by_3]-(list)`, and there is exactly
  one. Addressable events always get one, because a pubkey can hold many under different `d`
  values.
- **Stubs keep references alive.** A reply to a note we never saw still points at
  `(:Event {id})`. When the note arrives, the stub gains `:Stored`, and the reply's edge is
  already in place.
- **Unapply** (§6.2):
  1. delete the event's outgoing relationships and its properties;
  2. drop `:Stored`;
  3. delete the node if nothing points at it any more, otherwise keep it as a stub.

  Nodes left with no relationships (`:User`, `:Address`, `:Tag`, stubs) are removed by a
  periodic `sweepOrphans()`.

**Indexes:**
- the four uniqueness constraints;
- range indexes on `:Stored(created_at)` (the reconciler's windows) and `:Stored(kind)`;
- `:Stored(expires_at)`;
- `:User(nip05)`.

### 4.2 Relationships

Every relationship **originates at an `:Event:Stored`**, except `OWNED_BY`. Unapplying an event
therefore removes exactly its own contribution. No removal style needs graph-specific code.

| Type | From → To | Meaning |
|---|---|---|
| `by_<k>` | Event → User | Authorship. `<k>` is the event's kind. |
| `<t>_<k>` | Event → Event / User / Address / Tag | The event (kind `<k>`) carries the literal single-letter tag `<t>` naming that target. |
| `ref_<f>_<k>` | Event → Event / User / Address | A **derived** reference of family `<f>` ∈ {`e`, `p`, `a`}. It is a link Quartz names that is *not* a literal single-letter tag: a `nostr:` URI in content, a multi-letter tag (`zap`, `pinned`, `30382:rank`, …), or an embedded event. |
| `VERSION_OF` | Event → Address | This event is the address's current version. There is at most one per address. |
| `OWNED_BY` | Address → User | The address's pubkey. Stub addresses have it too, so a reference to an unseen article still reaches its author. |

- **Plain identifiers.** No backticks are needed. The prefixes (one letter plus `_`, `by_`,
  `ref_`) cannot collide. Case is significant: `e_1111` is a NIP-22 reply and `E_1111` is a
  NIP-22 root.
- **Kind in the type.** Neo4j groups a dense node's relationships by type and direction.
  Examples:
  - `(u)<-[:p_3]-()` walks only follow lists, not millions of mentions;
  - `COUNT { (u)<-[:p_3]-() }` is an **O(1)** follower count, read from the dense-node group;
  - `(n)<-[:e_7]-()` walks only reactions.
- **Kind registry.** Kinds known to the pinned Quartz (`EventFactory.isKnownKind`) get their own
  types. Other kinds share `<t>_other` / `ref_<f>_other` / `by_other`, which carry a `kind`
  property. This bounds the type count against spam kinds.
  - A Quartz bump that learns a kind moves its `_other` edges to the new type through the
    resumable `KindRegistryMigration`. It is recorded in `:Meta`.
  - That is a visible, additive schema change (§8.6).

**Relationship properties are sparse.** They exist only where the type leaves something open:

| Property | On | Meaning |
|---|---|---|
| `roles` | Types whose role-table row (§5.3) admits more than one role, e.g. `e_1` (root / reply / mention / fork), `e_7` (reaction / context), `P_9735` vs `ref_p_9735` | A list, e.g. `["root","reply"]` for a positional single-`e` reply. It is omitted where the type implies the role (e.g. `p_3` is always `follow`), and the implied role is documented in `docs/schema.md`. |
| `via` | `ref_…` | `content`, `embedded`, `description`, or the multi-letter tag name |
| `kind` | `_other` types | The source kind |
| curated values | See §4.3 | e.g. `rank` on `d_30382` |

**Not kept:**
- tag positions;
- relay hints;
- NIP-10 markers verbatim (they become `roles`);
- a per-edge timestamp. A time filter reads the source node's `created_at`.

### 4.3 Curated properties

Bodies stay in Vespa, but a few values are what graph queries actually filter or rank on.
Extracting them keeps whole classes of query inside Cypher. Each extractor is a small `Extractors`
entry built on a Quartz helper, and has its own golden test:

| Kind | Where | Property | Quartz source |
|---|---|---|---|
| 0 | `:User` | `name`, `display_name`, `nip05` (each ≤ 256 bytes) | kind-0 metadata parse. The current kind 0 wins, and unapplying it clears the values. |
| 7 | the reaction's `:Event` | `content` (≤ 32 bytes: `+`, `-`, an emoji or a `:shortcode:`) | `ReactionEvent.content` |
| 9735 | the receipt's `:Event` | `msats` | `ZapReceiptEvent.amount()` |
| 9734 / 9321 / 8333 / 9736 | the event | `msats` where the kind states an amount |  |
| 1984 | `p_1984` / `e_1984` | `report` (the report type) | `ReportEvent.reportedAuthorsWithOwnType()` |
| 30382 | `d_30382` (→ `:User`) | `rank`, `followers` | `UserAssertionEvent.rank()` / `followerCount()` |
| 30023, 30311, 34550 | the event | `title` (≤ 256 bytes) | the `title` tag |

Adding an extractor is an additive schema change. Existing events gain the value on the next
full reconcile pass, or by a targeted backfill.

### 4.4 Kind and tag policy (`schema/GraphPolicy`)

The policy is configuration, not schema. Its hash is stored in `:Meta`. A changed policy makes
the reconciler converge the graph to the new filter:
- a newly excluded kind is unapplied;
- a newly included kind is copied from Vespa.

- **Kinds.** An exclude list over "everything Vespa holds".
  - The default excludes **4, 1059, 21059** (DM metadata; D3).
  - Ephemeral kinds never reach Vespa.
  - Further candidates are chosen from vespa-relay's per-kind counts, e.g. 30078 app data and
    other unreferenced bulk, rather than guessed.
- **Tag nodes.** An allowlist of single-letter names that become `:Tag` nodes when their value
  is not a reference. The default is **`t` (hashtags), `i` (NIP-73 external ids), `k`, `l` /
  `L` (labels), `r`, `g`**.
  - Everything else is dropped: `d` (the `:Address` covers it), `x` (per-file hashes, one node
    per event, useless as joins), `m`, `alt`-like letters, and so on.
  - Values longer than 256 bytes are never nodes.
- **References are never dropped by name.** Every tag that resolves to an Event / User /
  Address becomes an edge (§5.2), whatever its letter. The policy only governs `:Tag` nodes and
  whole kinds.

### 4.5 Not modelled in v1

- `:Relay` nodes (NIP-65 read/write, relay hints).
- NIP-51 private (encrypted) members: the projection holds no keys.
- Trust-gated views: NIP-85 values are data (§4.3), not a filter.

---

## 5. Deriving the graph from an event (`derive/`)

`EdgeDeriver.derive(event: Event, policy): GraphDoc` is **pure**, with no I/O. It is the same
function in the live projector, in `InMemoryGraphIndex`, and in the bulk CSV writer, so the
three can never disagree about *what* the graph is.

The input is a **typed** Quartz event, built with
`EventFactory.create(id, pubkey, createdAt, kind, tags, content, sig)`, so
`event as? PubKeyHintProvider` works. Untyped kinds come back as a plain `Event` and take only
the generic path.

### 5.1 Step 1 — provider sets

Collect the provider sets from whichever providers the class implements:
- `L_e = linkedEventIds()`
- `L_p = linkedPubKeys()`
- `L_a = linkedAddressIds()`

All 109 implementing classes are listed in [`appendix-providers.md`](appendix-providers.md).

Normalize and validate each set:
- ids and pubkeys must be canonical lowercase 64-hex;
- addresses go through `Address.parse` and are re-serialized;
- everything else is dropped. Quartz's parsers only check the length, and
  `ATag.parseAddressId` returns any non-empty value.

### 5.2 Step 2 — classify every single-letter tag

For each tag `[n, v, …]` with `isIndexableTagName(n)`, the first rule that matches decides the
target:

1. `v ∈ L_e` → **Event** `v`.
2. `v ∈ L_p` → **User** `v`.
3. `v ∈ L_a` → **Address** `v`.
4. **Subject rules** (`LinkRules`), for references that live outside e/p/a:
   - `d` on **30382** → User;
   - `d` on **30383** → Event;
   - `d` on **30384** → Address.

   These are the NIP-85 assertion subjects, and they get `roles: ["asserts"]`.
5. **Generic fallback by name and value shape**, for kinds Quartz does not type or types without
   a provider (NIP-71 video, NIP-90 DVMs, NIP-29 groups, …):
   - `e` / `E` / `q` + 64-hex → Event;
   - `p` / `P` + 64-hex → User;
   - `a` / `A` / `q` + a valid address → Address.

   Quartz's `QTag.parseAddressId` rejects every address, because it refuses any value containing
   `:`. The fallback uses `QTag.parse`, which does not.
6. Otherwise → **Tag** `n:v`, if the policy allowlists `n` and `v` is at most 256 bytes. If not,
   the tag is dropped.

Rules 1–5 emit `<n>_<k>`, and rule 6 emits `<n>_<k>` to a `:Tag`. One edge is written per
(source, target, type), and the roles of duplicates are unioned.

**Why the providers come first:**
- They type the targets of tags whose name does not reveal the target: `z` parent lists,
  NIP-22 `E` / `A` / `P`, NIP-58 badge tags, NIP-72 approvals.
- They do so by kind semantics rather than by value shape.
- They are the only source of the derived links in step 3.

### 5.3 Step 3 — derived links and roles

**Derived edges.** Every id in `L_e ∪ L_p ∪ L_a` that no literal single-letter tag produced
becomes a `ref_<f>_<k>` edge. Its `via` records where it came from:
- `content`, for `nostr:` URIs in the 14 classes whose providers include `citedNIP19()`;
- the multi-letter tag name, e.g. `zap`, `pinned`, `exercise`, `template`;
- `embedded`.

**Link rules that fill Quartz gaps.** Each gap is also filed upstream (plan P0):

| Rule | Why |
|---|---|
| **Drop content `nsec1…` entities** from `L_p` | `ListEntityExt.pubKeys()` maps `NSec` to its hex, which is a **private key**. The deriver re-scans content and never writes a key that came from an `NSec`. This is a security requirement, covered by an invariant test. |
| Drop self-links (`v == event.id`) | `ChannelCreateEvent.linkedEventIds()` returns its own id. |
| **9735**: `ref_p_9735 {via:"description", roles:["zapper"]}` → the embedded zap request's author, and `ref_e_9735 {via:"description"}` → the request's id | The receipt's providers omit the sender. A literal `P` tag, when present, is already a `P_9735`. |
| **10040**: `ref_p_10040 {via:"<kind>:<service>"}` → the service pubkey, from `ServiceProviderTag` (tag name e.g. `30382:rank`) | The trust-provider list does not implement a provider. |
| **6 / 16**: `ref_e_<k>` / `ref_p_<k>` `{via:"embedded"}` → the embedded event's id and author, when no literal tag names them | Reposts often embed the original. |

**Roles.** `RoleTable` assigns roles per (kind, tag, marker), delegating to Quartz's helpers so
the semantics are Quartz's. `roles` is **stored only where the row admits more than one role**
(§4.2). Rows whose role is implied by the type are documented, not stored.

| Kinds | Tag → roles | Stored? | Quartz source |
|---|---|---|---|
| 1, 42, 1311, 2004, 30818, 1617, 1630–1633 | `e` → `root` / `reply` / `mention` / `fork` (NIP-10 markers; unmarked tags by the positional rule); `p` → `mention` | `e`: yes; `p`: implied | `MarkedETag.parse*`, `BaseThreadedEvent.root()` / `reply()`, `TextNoteEvent.isAFork()` |
| 1111, 1244 | `E` / `A` → `root`, `e` / `a` → `reply`, `P` → `root_author`, `p` → `reply_author` | implied by the letter's case | `CommentEvent.rootEventIds()` / `replyEventIds()` / … |
| any | `q` → `quote` | implied | `QTag.parse` |
| 6, 16 | last `e` / `a` → `repost`, others → `context`; `p` → `reposted_author` | `e` / `a`: yes | `BaseRepostEvent.boostedEventId()` / `boostedAddress()` |
| 7 | last `e` / `a` → `reaction` (NIP-25 target), others → `context`; `p` → `reacted_author` | `e` / `a`: yes | NIP-25 rule (store-side; Quartz has no "last e" helper) |
| 9734, 9735 | `e` / `a` → `zap`, `p` → `zapped`, `P` / embedded author → `zapper` | implied by type and `via` | `ZapReceiptEventInterface.zappedPost()` / `zappedAuthor()` / `zappedRequestAuthor()` |
| 5 | `e` / `a` → `delete` | implied | `DeletionRequestEvent.deleteEventIds()` |
| 1984 | `e` / `a` / `p` → `report` (+ `report` type, §4.3) | implied | `ReportEvent` |
| 1985 | `e` / `a` / `p` → `label` | implied | `LabelEvent.labeled*()` |
| 3 | `p` → `follow` | implied | `ContactListEvent` |
| 10000 | `p` → `mute` | implied | `MuteListEvent.publicMutes()` |
| 30000, 39089, 39092, 10017, 10020, 10101 | `p` → `member` | implied | NIP-51 `UserTag` |
| 10001 / 10003, 30001, 30003–30006, 30063, 30267, 10018 | `e` → `pin` / `e` + `a` → `bookmark` | implied | `EventBookmark` / `AddressBookmark` |
| 10004 / 34550 / 4550 | `a` → `community`; `p` on 34550 → `moderator`; `e` / `a` on 4550 → `approve` | implied | NIP-72 tag classes |
| 8 / 30008 / 10008 | `a` → `badge`; `p` on 8 → `awarded`; `e` on 30008 / 10008 → `award` | implied | NIP-58 |
| 30382 / 30383 / 30384 | `d` → `asserts` | implied | NIP-85 `aboutUser()` / `aboutEvent()` / `aboutAddress()` |
| 40–44 | `e` → `channel`; `e` on 43 → `hide`; `p` on 44 → `mute` | implied | NIP-28 `channelId()` |
| all others | no role; the edge is typed by tag and kind |  |  |

### 5.4 Worked example

A NIP-10 reply (kind 1, by `A`) with these tags:
- `["e",R,"wss://a","root"]`
- `["e",P,"","reply"]`
- `["p",X]`
- `["t","nostr"]`
- `["x","<sha256>"]`

and content `"… nostr:npub1<Y> … nostr:nsec1<Z> …"` projects to:

```
(ev:Event:Stored {id, kind:1, created_at})-[:by_1]->(:User {pubkey:A})
(ev)-[:e_1 {roles:["root"]}]->(:Event {id:R})
(ev)-[:e_1 {roles:["reply"]}]->(:Event {id:P})
(ev)-[:p_1]->(:User {pubkey:X})
(ev)-[:t_1]->(:Tag {key:"t:nostr"})
(ev)-[:ref_p_1 {via:"content"}]->(:User {pubkey:Y})
// x: not allowlisted, dropped. nsec1<Z>: never written.
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
- it drops excluded kinds (§4.4);
- it enqueues into a bounded in-memory queue;
- on overflow it drops the entry and marks that entry's `created_at` hour **dirty** for the
  reconciler. It never blocks Vespa.

A single consumer per process drains the queue in batches into `GraphIndex.apply` / `unapply`.
Each batch is one managed write transaction: `UNWIND` rows, `MERGE` on unique keys in sorted
order, dynamic relationship types (`CREATE (s)-[:$(row.type)]->(t)`), and driver retries on
deadlock.

**`apply(e)`:**
1. Already `:Stored` → no-op (idempotent).
2. If `e` is **replaceable**, the incumbent is the event on `(:User {pubkey})<-[:by_<k>]-(:Stored)`
   (for a `by_other` kind, the one with the same `kind` property).
   If `e` is **addressable**, the incumbent is the `VERSION_OF` source on its `:Address`.
   - If the incumbent wins under NIP-01 (higher `created_at`, then the lower id), skip `e`: it
     is a stale delivery.
   - Otherwise, unapply the incumbent in the same transaction.
3. If `e`'s id was **unapplied within the last hour** (the recent-removal table, below), skip
   it. It is a late put racing its own removal.
4. Write the node (promoting a stub if one exists), its edges, its `:Address` / `VERSION_OF` /
   `OWNED_BY`, and its curated values.

**`unapply(id)`:**
- apply the stub rule (§4.1);
- clear curated values it owned (e.g. kind-0 names);
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

There is no durable outbox in v1. The reconciler *is* the durability mechanism, and it is needed
anyway.

### 6.4 Fallback if the hook is not accepted

Forward the relay's **accepted events** and filter deletes from an `IEventStore` decorator, and
re-apply NIP-09 / NIP-62 in the projector. The graph makes these cheap:
- "is this deleted?" is `EXISTS { (:Stored {kind:5})-[:e_5|a_5]->(target) }` with the same
  author;
- vanish is an author-scoped unapply.

This costs a second, small policy implementation, plus reliance on the reconciler for Vespa-only
removals (sweeps, purges). It also requires fixing the relay's `as? VespaEventStore` casts.

---

## 7. Reconciliation and backfill (`reconcile/`)

### 7.1 The Vespa port

```kotlin
interface SourceOfTruth {                  // vespa-relay implements it over VespaEventStore.engine
    suspend fun visitIds(since: Long, until: Long, onPage: suspend (List<IdKindTime>) -> Boolean)
    suspend fun fetch(ids: List<String>): List<Event>
}
```

The implementation reads un-lensed and un-gated, via `EngineReads` (§6.1). The same `fetch`
serves Cypher hydration (§8.3).

### 7.2 `MirrorReconciler`

For a `created_at` window, the reconciler streams both sides in `(created_at, id)` order:
- Vespa, filtered to policy-included kinds;
- Neo4j `:Stored`.

It merge-diffs them:
- **missing** in Neo4j → `fetch` (chunks of 500) → `apply`;
- **extra** in Neo4j → `unapply`.

Windows are sized to about 250k ids. A 500M-id snapshot cannot be materialized; staging measured
~5.3 GiB for 43.7M ids.

Cadence:
1. Dirty hours first.
2. A rolling **recent pass**: the last 2 h, every 5 min.
3. A continuous **full sweep** over the whole corpus, resumable from a cursor file. Its period
   (a week or better) is measured in P7.

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
2. **Dump Vespa** with `EngineReads.visitDocsPage`, excluding policy kinds. Run each event
   through the **same `EdgeDeriver`**, and write node and relationship CSVs, one file per
   relationship type.
   - Resolve supersession duplicates with an external sort on the replaceable / addressable key.
     The dump spans time, so an old and a new version can both appear.
   - Deduplicate `:User` / `:Address` / `:Tag` / stub nodes.
3. **Import** with `neo4j-admin database import full`, then run `SchemaInstaller`, which creates
   the constraints and indexes.
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
   - `dbms.security.procedures.allowlist` = the same list;
   - no APOC / GDS plugins;
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
- `:User` is `{"pubkey", "name", …}`, `:Address` is `{"address", "kind", "pubkey", "d"}`, and
  `:Tag` is `{"name", "value"}`.
- A relationship is `{"type", "start", "end", …props}`, and a path is an alternating list.
- Integers outside ±2^53 are strings.

### 8.4 What callers see

Everything the projection holds, ungated by the relay's observer lens or trust floor. The
NIP-85 rank on `d_30382` edges lets a *query* apply a trust filter itself. DM metadata is not
there to see (§4.4).

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
  - the role table;
  - the kind registry;
  - the policy.
- **Versioning.**
  - Additive changes (a new extractor, role, allowlisted tag, or a kind promoted out of
    `_other`) bump the minor version. Queries over `_other` should filter by `kind`.
  - Renames and removals bump the major version, and are announced with migration notes.

### 8.7 Example queries (the reference battery)

These are also `ReferenceQueriesIT`, run against a fixture graph with known answers.

```cypher
// T1 — follower count (O(1) from the dense-node group) and the followers
MATCH (u:User {pubkey: $pk})
RETURN COUNT { (u)<-[:p_3]-() } AS followers;
MATCH (:User {pubkey: $pk})<-[:p_3]-(:Event)-[:by_3]->(f:User) RETURN f;

// T2 — follows-of-follows I don't follow, ranked by how many of my follows follow them
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)
      <-[:by_3]-(:Event)-[:p_3]->(fof:User)
WHERE fof <> me AND NOT EXISTS { (me)<-[:by_3]-(:Event)-[:p_3]->(fof) }
RETURN fof, count(DISTINCT f) AS via ORDER BY via DESC LIMIT 50;

// T3 — the whole NIP-10 thread under a root (every reply tags the root)
MATCH (root:Event {id: $id})<-[r:e_1]-(n:Stored)
WHERE 'root' IN r.roles
RETURN n ORDER BY n.created_at;

// T3b — the reply TREE, any depth, following reply edges
MATCH (root:Event {id: $id}) ((p)<-[r:e_1]-(c:Stored) WHERE 'reply' IN r.roles)+ (leaf)
RETURN leaf;

// T5 — notes my follows zapped this week, by total sats
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)
MATCH (f)<-[:P_9735|ref_p_9735]-(z:Stored)-[:e_9735]->(n:Stored {kind: 1})
WHERE z.created_at >= $since
RETURN n, sum(z.msats) AS msats, count(DISTINCT f) AS zappers ORDER BY msats DESC LIMIT 50;

// T9 — who NIP-85 provider S ranks >= 80, and how many of my follows follow each
MATCH (:User {pubkey: $service})<-[:by_30382]-(:Event)-[a:d_30382]->(u:User)
WHERE a.rank >= 80
OPTIONAL MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)
      <-[:by_3]-(:Event)-[:p_3]->(u)
RETURN u, a.rank, count(DISTINCT f) AS followedByMyFollows ORDER BY a.rank DESC;

// T11 — hashtags used alongside #bitcoin in the last day
MATCH (:Tag {key: 't:bitcoin'})<-[:t_1]-(n:Stored)-[:t_1]->(o:Tag)
WHERE n.created_at >= $since AND o.key <> 't:bitcoin'
RETURN o.value, count(*) AS uses ORDER BY uses DESC LIMIT 20;

// Hybrid — full-text search in Vespa first (ids from a NIP-50 REQ), then graph in Cypher
UNWIND $ids AS id
MATCH (n:Event:Stored {id: id})<-[:e_7]-(:Event)-[:by_7]->(r:User)
RETURN n, count(DISTINCT r) AS reactors ORDER BY reactors DESC;
```

`docs/schema.md` carries the full set:
- T4: reactions by my follows;
- T6: articles quoted by my follows;
- T7: badge awarded-and-worn;
- T8: community approvals;
- T10: most-cited missing events (stubs);
- T12: reporters of X that I follow.

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
| `./gradlew build` (unit) | Nothing | **Derivation:** one golden test per `appendix-providers.md` row, link rules, extractors, role table, and invariants (every single-letter tag lands in exactly one place or is dropped by policy; no self-edges; **no `nsec` ever becomes a `:User`**). **Projector semantics on `InMemoryGraphIndex`:** random interleavings of two feeds' puts and removes, plus drops, converge to the source's state after one reconcile. The source is Quartz's in-memory SQLite `EventStore` fed the same events. **`CypherGuard`** plan-walk unit tests on captured plans. **`ModuleBoundariesTest`, `PortDecoratorsTest`.** |
| `spotlessCheck` | Nothing | ktlint plus the MIT header |
| `-Pintegration` | Docker (`neo4j:2026.09-community`) | **`ProjectionIT`:** the same corpus and interleavings through `Neo4jGraphIndex` give `edgesOf(id)` identical to `InMemoryGraphIndex` for every id. **`CypherGuardIT`** (§8.5). **`ReferenceQueriesIT`** (§8.7). **`BulkImportIT`:** bulk path = online path. **`KindRegistryMigrationIT`.** |
| vespa-eventstore | Its own gates | `ObservedEventIndex` in `PortDecoratorsTest`. An IT: every mutation style (insert, supersession, kind 5, vanish, expiry, orphan sweep) reaches the observer, with exactly the removed ids. |
| vespa-relay `GraphProjectionIT` | Vespa + Neo4j | The relay's ingest with injected drops and a Neo4j pause → equal id sets after one reconcile, and client `OK` latency unaffected while Neo4j is paused. |

---

## 12. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Add the observer hook to vespa-eventstore (D2), or use the relay-side fallback (§6.4)? | **Hook.** It is exact, generic, and the pattern the store already uses internally. |
| Q2 | Final kind exclusions beyond DM metadata | Decide from vespa-relay's per-kind counts before the bulk import |
| Q3 | Neo4j host size | Decide from P7: page-cache fit vs query latency on the staging slice |
| Q4 | Cypher audience (`admin` / `auth` / `public`) | **`admin` until limits exist**, then widen |
| Q5 | Repo, group and package name. It is no longer an event store. | e.g. `nostr-graph` / `com.vitorpamplona.nostr.graph` |
| Q6 | Node economy vs convenience: keep 64-hex string ids, and no `pubkey` property on events? | Yes for v1 (readable, public contract). P7 measures compact ids. |

## 13. Later

- **Query limits**, from production measurements.
- **Trust-aware views** (observer lens over the `d_30382` ranks).
- **`:Relay` nodes.**
- **A bounded traversal DSL** for the Nostr wire (a REQ extension or a NIP-90 DVM).
- **More extractors**, as queries ask for them.
