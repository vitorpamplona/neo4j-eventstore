# neo4j-eventstore — specification

Status: **draft for review** (2026-09-29). The companion execution plan is [`plan.md`](plan.md).

A Neo4j-backed implementation of Quartz's `IEventStore` whose purpose is **traversal**, not
search: every event, every user and every address becomes a node, and every reference an event
makes to another event, user or address becomes a typed, directed relationship. That lets one
query hop across event kinds through their tags. Examples:

- Notes by my follows that were zapped by my follows.
- Replies, at any depth, under a thread root.
- Articles quoted by the people who reacted to me.
- Members of a community who wear a given badge.

It runs **beside** vespa-eventstore inside [vespa-relay](https://github.com/NosFabrica/vespa-relay)
(SearchOverTrust), holding the same events. Vespa keeps serving REQ / COUNT / NIP-50 / NIP-77;
Neo4j answers the questions Vespa cannot: multi-hop joins.

---

## 1. Goals and non-goals

**Goals**

1. **A correct `IEventStore`.** It applies the same NIP-01 / 09 / 40 / 62 write semantics as
   vespa-eventstore and Quartz's SQLite store, and it is gated by the same parity battery. It is
   usable on its own, and the relay can trust it to hold exactly what Vespa holds.
2. **A graph schema that captures as many references as Quartz can name.** It is derived from
   Quartz's `EventHintProvider`, `PubKeyHintProvider` and `AddressHintProvider` (the "hint
   providers"), completed by a literal-tag fallback so that no single-letter reference tag is
   lost on a kind Quartz does not type.
3. **A traversal query language** (`Traversal`). It is a typed Kotlin model with a JSON
   wire form, compiled to Cypher the way vespa-eventstore compiles `EventQuery` to YQL. An
   in-memory executable spec defines its semantics.
3b. **A read-only Cypher endpoint** (§8.2). This covers everything the traversal language cannot
   express: aggregations, path algorithms, ad-hoc exploration. The graph schema is therefore a
   **public query contract**, documented and versioned (§8.2.6).
4. **Synced with the Vespa store in vespa-relay.** Every event Vespa accepts reaches Neo4j,
   except the DM-metadata kinds the relay deliberately does not replicate (D3, §8.2.4). Drift
   from crashes, queue overflow, or Vespa-only maintenance is detected and repaired automatically.
5. **The same engineering model as vespa-eventstore:**
   - modules `:engine` / `:store` / `:benchmark`;
   - a port with an executable in-memory spec;
   - decorators guarded by source-reading tests;
   - a hermetic unit gate plus a testcontainers integration gate;
   - Quartz pinned by commit;
   - dense *why*-comments.

**Non-goals**

- **NIP-50 full-text search.** A filter carrying search terms answers empty, and COUNT answers 0.
  This is the only lawful answer an `IEventStore` without an index can give (see §6.4).
- **Serving the relay's REQ traffic.** `IEventStore.query` is complete and correct here, but it is
  not optimized for newest-first feed reads at 200M+ events. The relay keeps routing REQs to
  Vespa.
- **The NIP-85 observer gate / trust ranking.** This is out of scope for v1. Traversals are
  lens-free. Phase 8 of the plan revisits trust-aware traversal.
- **Embedding Neo4j in-process.** Neo4j Community Server is GPLv3, and linking it would put the
  artifact under GPL terms. We only ever talk to a separate server over Bolt through the
  Apache-2.0 Java driver (see §11).

---

## 2. The central decision: reuse the policy layer, replace the engine

vespa-eventstore separates two things:

- **the index port and its binding.** This covers `EventIndex`, a document, and how a query
  compiles and travels.
- **relay policy.** This is `NostrSemanticsStore`: write serialization, dedup,
  replaceable/addressable supersession, NIP-09 deletion, NIP-40 expiration, NIP-62 vanish, page
  assembly and NIP-45 counting.

`NostrSemanticsStore(index: EventIndex, …)` has a **public constructor that accepts any
`EventIndex`**, and `InMemoryEventIndex` already proves the policy runs over a non-Vespa engine.

**Decision D1 — neo4j-eventstore implements vespa-eventstore's `EventIndex` port and runs the
unmodified `NostrSemanticsStore` over it.** We copy the *model* (port + executable spec +
decorators + gates), and we *reuse* the policy rather than forking it.

Why:

- **Sync by construction.** Two stores fed the same accepted events converge only if they apply
  the same rules. The rules are designed so that the final state is a function of the *set* of
  events received, not their order:
  - a newer replaceable wins in either order;
  - a deletion blocks a later insert and erases an earlier one;
  - a vanish request does the same for a whole author.

  Running the *same code* makes "same rules" a fact rather than a hope, including the edge cases
  vespa-eventstore has already fixed: deleting a deletion is a no-op, only same-owner deletions
  count, and gift wraps are deleted by their p-tag.
- **~1,100 lines of `NostrSemanticsStore` plus `ingest/`, which we don't re-derive or re-audit.**
  Every future fix lands in both stores with one version bump.
- **The port's contract is easy for Neo4j to meet.** The contract is read-your-writes per
  document, and an acked `put` is visible to `search`. Neo4j transactions are ACID, so a
  committed write is visible to the next read.

Costs, and how we contain them:

| Cost | Containment |
|---|---|
| `EventQuery` carries Vespa-only fields (`search`, `phrases`, `rankKey`, `ranking`, `minRank`, …). | The Neo4j index is **text-blind and trust-blind**, exactly like `InMemoryEventIndex` already is. It answers search-bearing queries with nothing (§6.4) and ignores rank fields. |
| `NostrSemanticsStore` rejects text Vespa can't store (`UNSTORABLE_TEXT`). | This is a feature: the same events are rejected on both sides, which keeps the sets equal. |
| The two stores' versions are coupled through `com.nosfabrica.vespa.eventstore:store`. | This is intentional. vespa-relay `force`s one pin for both, as it already does for Quartz (plan §R1). |
| The package name `com.nosfabrica.vespa.eventstore` appears in a Neo4j store's API. | It is confined to `:engine`'s port implementation. Consumers see `Neo4jEventStore` / `IEventStore` / `GraphReads`. |

**Fallback, if the port turns out too Vespa-shaped:** fork `NostrSemanticsStore` + `ingest/`
into `:store` (the literal "copy"), keep the same tests, and add a cross-store convergence test.
The graph schema, traversal language and sync design below are unaffected by this choice.

---

## 3. Architecture

```
                       ┌───────────────────── :store (facade) ─────────────────────┐
IEventStore ◀──────────│ Neo4jEventStore.open(...)                                  │
GraphReads  ◀──────────│   NostrSemanticsStore( Metered( Neo4jEventIndex ) )        │  ← policy reused (D1)
                       │   GraphReads( Metered( Neo4jGraphIndex ) )                 │  ← traversal
                       │   mirror/: StoreMirror, MirrorReconciler (IEventStore→IEventStore)
                       └────────────────────────────────────────────────────────────┘
                       ┌───────────────────── :engine ─────────────────────────────┐
                       │ schema/  DDL + SchemaInstaller + migrations                │ layer 0
                       │ doc/     GraphDoc derivation: EdgeDeriver, RoleTable,      │ layer 1
                       │          RelTypes, KindRegistry, LinkRules                 │
                       │ query/   EventQuery→Cypher (EventCypher),                  │ layer 2
                       │          Traversal model + TraversalCypher                 │
                       │ (root)   GraphIndex port (+ implements EventIndex)         │ layer 3
                       │ metrics/ MeteredGraphIndex                                 │ layer 4
                       │ memory/  InMemoryGraphIndex  (EXECUTABLE SPEC)             │ layer 5
                       │ client/  Neo4jGraphIndex (Bolt, neo4j-java-driver)         │ layer 5
                       └────────────────────────────────────────────────────────────┘
                       :benchmark — parity/traversal ITs, corpus loaders, load/latency probes (unpublished)
```

- **Maven coordinates:** `com.vitorpamplona.neo4j.eventstore:{engine,store}`. The group is an
  open question (§13).
- **Toolchain:** Kotlin 2.4 and JDK 21, both matching vespa-eventstore.
- **Quartz** comes from JitPack, pinned by commit, at the same pin vespa-eventstore uses.
- **vespa-eventstore** is `api("com.nosfabrica.vespa.eventstore:store")`, pinned.

**The ports.**

- `Neo4jGraphIndex` implements **both** vespa-eventstore's `EventIndex` (what the policy layer
  drives) and our own `GraphIndex` (traversal reads).
- `InMemoryGraphIndex` implements both too. It is the **executable specification** of the graph
  derivation and of `Traversal` semantics. For `EventQuery` matching it delegates to
  vespa-eventstore's `InMemoryEventIndex`, so the NIP-01 semantics have a single spec.

```kotlin
interface GraphIndex : AutoCloseable {
    /** Runs a [Traversal]; see §7 for semantics. */
    suspend fun traverse(t: Traversal): TraversalResult
    /** O(1) degree for one relationship type/direction (dense-node counts), e.g. follower count = degree(User, "p_3", IN). */
    suspend fun degree(node: NodeRef, type: String, dir: Direction): Long
    /** The edges one stored event contributed, as derived — the debug/explain surface (/graph/explain/{id}). */
    suspend fun edgesOf(eventId: String): List<EdgeView>
    /** Relationship types currently present (the compiler expands tag/role wildcards against this). */
    suspend fun relTypes(): Set<String>
}
```

**Layering and guard tests** are copied from vespa-eventstore and adapted:

- `ModuleBoundariesTest` holds the layer table above, forbids facade imports from below, and
  requires that a test named after a class lives in that class's package.
- `PortDecoratorsTest` checks every decorator overrides **every** member of both `EventIndex`
  and `GraphIndex`.

A decorator that inherits `EventIndex`'s default `existingIds` / `visitIds` / `countByAuthor`
answers with the slow default instead of decorating. The vespa-eventstore test exists for
exactly that trap.

---

## 4. Graph data model

### 4.1 Nodes

| Label | Key (unique constraint) | Properties | Created by |
|---|---|---|---|
| `:Event:Stored` | `id` | `pubkey`, `kind`, `created_at`, `d` (addressables), `expires_at` (NIP-40, else absent), `tags` (canonical JSON), `content`, `sig`, `owner` (gift-wrap recipient, else absent) | `put` |
| `:Event` (stub, no `:Stored`) | `id` | none | An edge to an event id that is not stored (yet, or any more) |
| `:User` | `pubkey` | none in v1 | An author, or a referenced pubkey |
| `:Address` | `id` = `kind:pubkey:d` (Quartz `AddressSerializer` form; replaceables end in `:`) | `kind`, `pubkey`, `d` | Any replaceable/addressable event, or a reference to one |
| `:Tag` | `key` = `name:value` | `name`, `value` | A single-letter tag whose value is **not** a reference (`t`, `d`, `k`, `r`, `i`, `g`, `l`, `L`, `m`, `x`, …, and malformed e/p/a values) |
| `:Meta` | singleton | `schema_version`, `kind_registry_version`, `quartz_pin`, `store_pin` | `SchemaInstaller` |

- **Stubs keep references alive.** A reply whose parent we never saw, or whose parent was
  deleted, still points at `(:Event {id})`. When the parent arrives it is merged and labelled
  `:Stored`, and the reply's edge is already there.
- **Removing a stored event** (`remove`) deletes its **outgoing** relationships and properties
  and drops `:Stored`. If the node still has incoming edges it remains a stub; otherwise it is
  deleted.
- **`get` / `search` / `existingIds` / counts only ever see `:Stored`.**
- `:User` / `:Address` / `:Tag` / stub nodes left with no relationships are garbage. A periodic
  `sweepOrphans()` removes them (not needed for correctness).

**Indexes:**

- the four uniqueness constraints;
- range indexes on `:Stored(created_at)`, `:Stored(kind, created_at)`, `:Stored(pubkey, kind)`
  and `:Stored(expires_at)`.

The query compiler's plans are pinned by `PROFILE`-shape tests (plan Phase 3).

### 4.2 Relationships

Every relationship **originates at an `:Event:Stored`**. Removing an event therefore removes
exactly its own contribution, and supersession / NIP-09 / NIP-62 / expiry need **zero
graph-specific code**: they all funnel into `remove`, the same property vespa-eventstore's
`TrustProjection` relies on.

| Type | From → To | Meaning |
|---|---|---|
| `` `by_<k>` `` | Event → User | Authorship, `<k>` = the event's kind |
| `` `<t>_<k>` `` | Event → Event \| User \| Address \| Tag | The event (kind `<k>`) carries the literal single-letter tag `<t>` pointing there |
| `` `ref_<f>_<k>` `` | Event → Event \| User \| Address | A **derived** reference of family `<f>` ∈ {`e`,`p`,`a`}: a link Quartz names that is *not* a literal single-letter tag (content `nostr:` URIs, multi-letter tags such as `zap` / `pinned` / `exercise` / `30382:rank`, embedded events) |
| `VERSION_OF` | Event → Address | This stored event is the current version of that address. There is at most one incoming per address, because the policy keeps only the winner. |
| `OWNED_BY` | Address → User | The address's pubkey. This exists for stubs too, so a reference to an unseen article still reaches its author. |

**Type names are plain Cypher identifiers.** They are part of the public query contract (§8.2),
so users can write `(:User)<-[:p_3]-()` without backticks. A single-letter tag prefix (`p_`,
`E_`), `by_` and `ref_` can never collide. Neo4j type names are case-sensitive, so `e_1111`
(NIP-22 reply) and `E_1111` (NIP-22 root) stay distinct.

**Kind in the type name.** Neo4j groups a dense node's relationships by **type and direction**.
Putting the source kind in the type makes these expansions touch only the relevant
relationships:

- "kind-3 lists that p-tag X": `(x)<-[:p_3]-()`
- "reactions to note N": `(n)<-[:e_7]-()`

An account with millions of incoming p-tags does not scan its mentions to find its followers.
It also makes degree **O(1)**: `COUNT { (u)<-[:p_3]-() }` is the follower count, read from
the dense-node group.

**Kind registry.** Only kinds in the store's `KindRegistry` get their own type. The default
registry is every kind `EventFactory.isKnownKind` recognises at the pinned Quartz. Other kinds
collapse to `` `<t>_other` `` / `` `ref_<f>_other` `` / `` `by_other` `` with a `kind` relationship property.

- This bounds the relationship-type count: Quartz knows ~400 kinds × the tag letters actually
  used. Neo4j's type-token limit is not reached by spam kinds.
- A Quartz bump that learns a new kind moves existing `_other` edges to the new type through
  `KindRegistryMigration`. It runs once at `open()`, is resumable, and is recorded in `:Meta`,
  the same pattern as vespa-eventstore's `TrustKeyingMigration`.

**Relationship properties** (all optional; absent when empty):

| Property | On | Meaning |
|---|---|---|
| `pos` | `<t>_…` | Index of the first tag in the event's tag array that produced this edge |
| `relay` | `<t>_…`, `ref_…` | The relay hint (tag element 2, or the NIP-19 TLV relay) |
| `marker` | `e_…`, `a_…`, `q_…` | Tag element 3 verbatim (`root` / `reply` / `mention` / `fork` / …) |
| `roles` | `<t>_…`, `ref_…` | The semantic roles, from the role table (§5.3), e.g. `["root","reply"]` |
| `via` | `ref_…` | Where a derived link came from: `content`, `embedded`, or the multi-letter tag name |
| `at` | all | The source event's `created_at`, denormalized so time-bounded expansions filter without touching the source node |
| `kind` | `_other` types only | The source kind |

**One edge per (source, target, type).** Duplicate tags collapse. A tag that names the same
target twice with different markers unions its `roles`.

### 4.3 What the model deliberately does not do

- It does not make **`:Relay` nodes** in v1. Relay hints live on edges as `relay`, and `r` tags
  are `:Tag` nodes. Relay nodes (NIP-65 read/write, hint graphs) are plan Phase 8.
- It does not index **NIP-51 private (encrypted) members**. The store holds no keys.
- It does not index **multi-letter tags as `:Tag` nodes.** NIP-01 cannot filter on them. They
  contribute only the derived references their typed class names.

---

## 5. Deriving the graph from an event (`doc/`)

`EdgeDeriver.derive(event: Event): GraphDoc` is pure. It needs no store and no I/O, and it
produces the event node, its address (if any), and the list of edges. Both engines call the same
function, so the in-memory spec and Neo4j cannot disagree about *what* the graph is, only about
how they store it.

The input is a **typed** Quartz event:

- `EventDoc` → `EventFactory.create(id, pubkey, createdAt, kind, tags, content, sig)`, so
  `event as? PubKeyHintProvider` works.
- Untyped kinds come back as a plain `Event` and take the generic path only.

### 5.1 Step 1 — provider sets

For a typed event, collect:

- `L_e = linkedEventIds()`
- `L_p = linkedPubKeys()`
- `L_a = linkedAddressIds()`

These come from whichever providers the class implements. Every provider implementation in
Quartz as of the pin is catalogued in [`appendix-providers.md`](appendix-providers.md): 109
concrete classes. The golden tests in plan Phase 2 are generated from that table.

Normalize and validate each set:

- ids and pubkeys must be canonical lowercase 64-hex;
- addresses go through `Address.parse` and are re-serialized canonically;
- anything invalid is dropped. Quartz's parsers only check length (`ATag.parseAddressId` returns
  any non-empty value).

### 5.2 Step 2 — classify every single-letter tag

For each tag `[n, v, …]` with `isIndexableTagName(n)` (Quartz: one ASCII letter), in order, the
**first** rule that matches decides the target:

1. `v ∈ L_e` → **Event** `v`.
2. `v ∈ L_p` → **User** `v`.
3. `v ∈ L_a` → **Address** `v`.
4. **Subject rules** (`LinkRules`) for kinds whose reference lives outside e/p/a, which the
   providers miss by design:
   - `d` on **30382** → User;
   - `d` on **30383** → Event;
   - `d` on **30384** → Address.

   These are the NIP-85 assertion subjects. The edge gets `roles:["asserts"]`.
5. **Generic fallback by tag name and value shape.** This applies to kinds Quartz does not type,
   or does type without a provider (e.g. NIP-71 video, NIP-90 DVM, NIP-29 groups, gift wraps):
   - `e` / `E` + 64-hex → Event;
   - `p` / `P` + 64-hex → User;
   - `a` / `A` + a valid address → Address;
   - `q` + 64-hex → Event;
   - `q` + a valid address → Address. Quartz's `QTag.parseAddressId` rejects every address
     because it refuses a `:`; `QTag.parse` does not, and the fallback uses it.
6. Otherwise → **Tag** `n:v`, if `v` is at most 1,024 UTF-8 bytes; longer values are not
   indexed (§6.3).

This emits `` `<n>_<k>` `` with `pos`, `relay` (element 2 if it normalizes as a relay URL) and
`marker` (element 3).

**Why the providers come first even though rule 5 would catch most of them:**

- They decide the target **type** for tags whose name does not reveal it: `z` parent lists,
  `E`/`A`/`P` roots, the NIP-58 badge `a`/`e`, NIP-72 approval tags.
- They decide it per **kind semantics** rather than by value shape.
- They are the only source of the derived links in step 3.

### 5.3 Step 3 — derived links and roles

**Derived edges.** Every id in `L_e ∪ L_p ∪ L_a` that **no literal single-letter tag produced**
becomes a `` `ref_<f>_<k>` `` edge. The `via` property is:

- `content` for ids found by `findNostrUris(content)`, i.e. the classes whose providers include
  `citedNIP19()`, 14 of them;
- the multi-letter tag name, e.g. `zap`, `pinned`, `exercise`, `template`;
- `embedded` for ids taken from an embedded event.

**Link rules that fill Quartz gaps.** Each gap below is also filed upstream (plan Phase 0):

| Rule | Why |
|---|---|
| **Drop content `nsec1…` entities** from `L_p` | `ListEntityExt.pubKeys()` maps `NSec` to its hex, a *private key*. The deriver re-scans content, removes any hex that came from an `NSec`, and never writes it. This is a security requirement: tested, never optional. |
| Drop self-links (`v == event.id`) | `ChannelCreateEvent.linkedEventIds()` returns its own id. |
| **Kind 9735**: add `ref_p_9735 {via:"description", roles:["zapper"]}` → the embedded zap request's author, and `ref_e_9735 {via:"description"}` → the request's id | The receipt's providers omit the sender. The sender is the most useful zap edge ("who zapped whom"). A literal `P` tag, when present, is already a `P_9735`. |
| **Kind 10040**: `ref_p_10040 {via:"<kind>:<service>"}` → service pubkey, per `ServiceProviderTag` (tag name e.g. `30382:rank`) | The trust-provider list names services in multi-letter tags. It does not implement a provider. |
| **Kinds 6/16**: `ref_e_<k>` / `ref_p_<k>` `{via:"embedded"}` to the embedded event's id and author, when the content carries it and no literal tag names them | Reposts often embed the original. |
| Kind 30023 / 2004 `a` tags | Their providers omit plain `a`. Rule 5 already catches the literal `a`, so this rule is **none needed**; it is listed so nobody "fixes" it twice. |

**Roles.** `RoleTable` assigns `roles` per (kind, tag name, marker) using Quartz's own helpers,
so the thread and zap semantics are Quartz's and are not re-implemented:

| Kinds | Tag → roles | Quartz source |
|---|---|---|
| 1, 42, 1311, 2004, 30818, 1617, 1630–1633 | `e` → `root` / `reply` / `mention` / `fork` by NIP-10 marker; unmarked tags by the positional rule; `p` → `mention` | `MarkedETag.parse*`, `BaseThreadedEvent.root()` / `reply()`, `TextNoteEvent.isAFork()` |
| 1111, 1244 | `E`/`A` → `root`, `e`/`a` → `reply`, `P` → `root_author`, `p` → `reply_author`, `K`/`k` → Tag | `CommentEvent.rootEventIds()` / `replyEventIds()` / … |
| any | `q` → `quote` | `QTag.parse` |
| 6, 16 | last `e`/`a` → `repost`, `p` → `reposted_author` | `BaseRepostEvent.boostedEventId()` / `boostedAddress()` |
| 7 | last `e`/`a` → `reaction` (NIP-25 target), earlier ones → `context`, `p` → `reacted_author` | NIP-25 rule. Quartz has no "last e" helper, so this is store-side. |
| 9734, 9735 | `e`/`a` → `zap`, `p` → `zapped`, `P` / embedded author → `zapper` | `ZapReceiptEventInterface.zappedPost()` / `zappedAuthor()` / `zappedRequestAuthor()` |
| 5 | `e`/`a` → `delete` | `DeletionRequestEvent.deleteEventIds()` |
| 1984 | `e`/`a`/`p` → `report`, plus `rtype` = report type | `ReportEvent.reportedAuthorsWithOwnType()` |
| 1985 | `e`/`a`/`p` → `label` | `LabelEvent.labeled*()` |
| 3 | `p` → `follow` | `ContactListEvent` |
| 10000 | `p` → `mute` | `MuteListEvent.publicMutes()` |
| 30000, 39089, 39092, 10017, 10020, 10101 | `p` → `member` | NIP-51 `UserTag` |
| 10001 | `e` → `pin` |  |
| 10003, 30001, 30003–30006, 30063, 30267, 10018 | `e`/`a` → `bookmark` | `EventBookmark` / `AddressBookmark` |
| 10004 / 34550 / 4550 | `a` → `community`, `p` on 34550 → `moderator`, `e`/`a` on 4550 → `approve` | NIP-72 tag classes |
| 8 / 30008 / 10008 | `a` → `badge`, `p` on 8 → `awarded`, `e` on 30008/10008 → `award` | NIP-58 |
| 30382 / 30383 / 30384 | `d` → `asserts` | NIP-85 (`aboutUser()` / `aboutEvent()` / `aboutAddress()`) |
| 40–44 | `e` → `channel` (41/42 root), `e` on 43 → `hide`, `p` on 44 → `mute` | NIP-28 `channelId()` |
| everything else | *no role*; the edge still exists, typed by tag and kind |  |

The role table is **data** (`RoleTable.kt`) with one golden test per row. Roles are a convenience
for traversal (§7.2 `role:` steps). They never affect NIP-01 filter semantics, which use only the
literal `<t>_<k>` edges.

### 5.4 Worked example

A NIP-10 reply (kind 1) with `["e",R,"wss://a","root"]`, `["e",P,"","reply"]`, `["p",X]`,
`["t","nostr"]`, and content `"… nostr:npub1<Y> …"` produces:

```
(ev:Event:Stored {id, kind:1, …})-[:by_1]->(:User {pubkey: author})
(ev)-[:`e_1` {pos:0, relay:"wss://a/", marker:"root",  roles:["root"],  at}]->(:Event {id:R})
(ev)-[:`e_1` {pos:1,                  marker:"reply", roles:["reply"], at}]->(:Event {id:P})
(ev)-[:`p_1` {pos:2, roles:["mention"], at}]->(:User {pubkey:X})
(ev)-[:`t_1` {pos:3, at}]->(:Tag {key:"t:nostr"})
(ev)-[:`ref_p_1` {via:"content", roles:["mention"], at}]->(:User {pubkey:Y})
```

---

## 6. `EventIndex` over Neo4j (`client/Neo4jGraphIndex`)

### 6.1 Writes

- **`put` / `putAll`**: one managed write transaction per batch, running
  `UNWIND $rows …`. The row is the `GraphDoc`.
  - Key nodes are `MERGE`d on their unique constraints, in sorted key order to minimise deadlocks.
  - Relationships are created with dynamic types (`CREATE (s)-[:$(row.type)]->(t)`, Cypher 25).
  - The driver's `executeWrite` retries transient errors and deadlocks.
  - `putAll` returns only after commit, which satisfies the port contract that "all acked and
    visible on return".
- **`remove` / `removeAll` / `removeDocs`**: the stub rule of §4.1, batched.
- **`putIfNewer`, v1**: inherit the port's default (read, compare with `EventDoc.NEWEST_FIRST`,
  `removeDocs` + `put`). `supersedesViaPut = false`.
- **`putIfNewer`, Phase 6 optimization**: an engine-atomic override. One transaction locks the
  `:Address` node, compares with its `VERSION_OF` incumbent, and swaps. Then
  `supersedesViaPut = true`, and the bulk path skips its version-read stage. The override must
  keep the NIP-01 tiebreak (highest `created_at`, then the lowest id wins).

### 6.2 Reads — `EventQuery` → Cypher (`query/EventCypher`)

The compiler picks an **anchor** and then filters:

1. **`ids`**: a unique-index seek.
2. **Any `tags` / `tagsAll` entry**: anchor on the most selective one.
   - Hex-valued lookups go to Event/User.
   - Address-valued lookups go to Address.
   - Everything else goes to Tag.
   - The compiler **unions every node kind the literal could live in**, so it never trusts
     classification for filter correctness. Each branch is a unique-key seek, so the union is
     cheap.
   - It expands **incoming** `` `<t>_<k>` `` edges. Known `kinds` give exact types; otherwise it
     uses every `<t>_…` type in `relTypes()`.
   - Remaining predicates are applied to the source event: other tags as `EXISTS {…}`,
     `kinds`, `authors`, `since`/`until` (inclusive), `notExpiredAt`, `expiresBefore`.
3. **`authors`**: expand `` `by_<k>` `` from each `:User`, or use `:Stored(pubkey, kind)`.
4. **`kinds` only**: `:Stored(kind, created_at)`.
5. **Nothing**: `:Stored(created_at)`.

Ordering is `created_at DESC, id ASC`, then `LIMIT`. A `limit <= 0` answers nothing without a
query. `count` ignores a positive limit (port contract).

The streaming members are all keyset-paged on `(created_at, id)` or `id`, never
`SKIP`-paged, so a 200M-event walk is O(page) memory:

- `visitIds`
- `visitTags`
- `visitDocsPage`
- `scanAuthors`

`countByAuthor` is one aggregation. `distinctTagIndexValues` is answered from `:Tag`
(`MATCH (t:Tag {name:$n})<-[…]-(e) … RETURN DISTINCT t.value`), which is exactly the aggregate
the port asks for.

### 6.3 Divergences, annotated

These use the rule ids of Quartz's event-store-semantics contract:

- **Tag values over 1,024 bytes** are not indexed, so a `#x` filter on such a value matches
  nothing (**STORE-F divergence**). SQLite matches it.
- **Uppercase / non-canonical hex** in e/p tags lands on a `:Tag` node, **not** a User/Event.
  Filters still match literally. The divergence is only that the value does not join the graph.
- **Gift wraps** (1059): `owner` is set from the `p` recipient exactly as `EventDoc.owner`
  computes it, so the policy's gift-wrap rules hold.

### 6.4 Search-bearing queries

When `search` / `phrases` / `notSearch` is non-empty:

- `search` / `rawSearch` / `searchRanked` answer `[]`;
- `count` answers 0;
- `visit*` stream nothing.

This matches the `IEventStore` contract: a store must ignore NIP-50 extensions it doesn't
support, and it has no terms index. Rank fields (`rankKey`, `followersKey`, `ranking`, `minRank`,
`memberFloor`, `authorWeights`, …) are **ignored**. Like `InMemoryEventIndex`, this store holds
no reputation data, so nothing is gated and served == matched.

---

## 7. The traversal language

### 7.1 Model

```kotlin
data class Traversal(
    val start: Start,
    val steps: List<Step>,
    val result: ResultSpec = ResultSpec(),
)

sealed interface Start {
    data class Filter(val filter: com.vitorpamplona.quartz.nip01Core.relay.filters.Filter) : Start // NIP-01 start set, search must be null
    data class Users(val pubkeys: List<HexKey>) : Start
    data class Events(val ids: List<HexKey>) : Start
    data class Addresses(val ids: List<String>) : Start
    data class Tags(val name: String, val values: List<String>) : Start
}

sealed interface Step {
    val where: NodeFilter?          // applied to the node the step ARRIVES at
    /** Event → what it references. */
    data class Out(val tags: Set<String> = ANY, val roles: Set<String> = ANY, val derived: Boolean = true, ...) : Step
    /** Any node ← the events that reference it; `kinds` = kinds of the REFERENCING events. */
    data class In(val tags: Set<String> = ANY, val roles: Set<String> = ANY, val kinds: Set<Int> = ANY, val derived: Boolean = true, ...) : Step
    data object Author : Step                       // Event → User
    data class Authored(val kinds: Set<Int> = ANY, ...) : Step   // User → Event
    data object Current : Step                      // Address → its stored version
    data object AddressOf : Step                    // Event → its Address
    data object Owner : Step                        // Address → User
}

data class NodeFilter(                              // the NIP-01 subset that makes sense per node
    val kinds: Set<Int>? = null, val authors: Set<HexKey>? = null,
    val since: Long? = null, val until: Long? = null,
    val tags: Map<String, List<String>>? = null,    // literal, as NIP-01
    val stored: Boolean? = null,                    // include stubs? default: events must be :Stored
    val exclude: Start? = null,                     // e.g. "not people I already follow"
)
// Any set-valued NodeFilter field (and `exclude`) may instead name an earlier step's output with
// `Ref(name)` — the `as` / `{"ref": …}` of the wire form — so one set is reused without a second query.

data class ResultSpec(
    val nodes: Returning = Returning.LAST,          // LAST | EVENTS | USERS | ADDRESSES | TAGS
    val order: Order = Order.RECENT,                // RECENT | PATHS (distinct paths reaching the node, desc) | NONE
    val limit: Int? = null,                         // the caller's own LIMIT; null = every result
    val count: Boolean = false,                     // COUNT-like: size of the (deduplicated) final set
    val hydrate: Boolean = true,                    // return full events (else ids / keys only)
)
```

**Semantics.** The semantics are defined by `InMemoryGraphIndex`, not by Cypher:

1. Every step maps a *set* of nodes to a *set* of nodes. It is `DISTINCT` after every step,
   except that under `order = PATHS` the path multiplicity is kept as a weight.
2. Events must be `:Stored` unless `where.stored = false`. Stubs are reachable, which is how you
   ask "what do people keep replying to that we don't have?".
3. `Out` / `In` with `roles` expand to the relationship types whose role table can produce that
   role, then filter on `roles`. `tags` restricts by the literal tag letter. `derived = false`
   excludes `ref_…` edges.
4. **No server-side limits in v1.** There is no cap on steps, per-node fanout, intermediate set
   size, result size or run time. Every answer is complete. Limits are added later, from
   production measurements (plan P7). The model leaves room for them: a future cap would add a
   `truncated` flag to `TraversalResult`, not change these semantics.

### 7.2 Wire form (JSON)

Here is one query: *notes posted by the people I follow in the last week that someone I follow
zapped, ranked by how many of my follows zapped each*.

```json
{
  "start": { "users": ["<me>"] },
  "steps": [
    { "authored": { "kinds": [3] } },
    { "out": { "tags": ["p"] }, "as": "follows" },
    { "in":  { "roles": ["zapper"], "kinds": [9735] } },
    { "out": { "roles": ["zap"] }, "where": { "kinds": [1], "since": 1758585600,
                                               "authors": { "ref": "follows" } } }
  ],
  "result": { "order": "paths", "limit": 100 }
}
```

- `as` names a step's output set so a later `where` can reference it (`{"ref": …}`). This is
  what makes "people I follow" usable twice without a second query.
- The third step walks from each followed user to the zap receipts naming them as `zapper`. That
  role is produced by both the literal `P_9735` tag and the derived `ref_p_9735 {via:"description"}`
  edge (§5.3), so the step matches whichever the receipt carries.

The Kotlin model and the JSON codec live in `:store/mapping/`. The codec rejects unknown keys:
a typo is an error, not a silently wider query.

### 7.3 Compilation (`query/TraversalCypher`)

Each step becomes a `CALL (n) { … }` subquery, followed by `WITH DISTINCT …`. Named sets become collected lists. `PATHS` ordering carries
a `count(*)` through the chain. The example above compiles to roughly this:

```cypher
MATCH (me:User {pubkey: $me})
CALL (me) { MATCH (me)<-[:by_3]-(l:Event:Stored) RETURN l }      // at most one: kind 3 is replaceable
CALL (l)  { MATCH (l)-[:p_3]->(f:User) RETURN f }
WITH collect(DISTINCT f) AS follows
UNWIND follows AS f
CALL (f)  { MATCH (f)<-[r:ref_p_9735|P_9735]-(z:Event:Stored) WHERE 'zapper' IN r.roles
            RETURN z }
CALL (z)  { MATCH (z)-[r]->(n:Event:Stored) WHERE type(r) IN $zapTypes AND 'zap' IN r.roles
              AND n.kind = 1 AND n.created_at >= $since
              AND EXISTS { (n)-[:by_1]->(a:User) WHERE a IN follows }
            RETURN n }
RETURN n, count(*) AS paths ORDER BY paths DESC, n.created_at DESC, n.id LIMIT 100
```

`TraversalParityIT` runs every traversal in the battery on both `InMemoryGraphIndex` and Neo4j
over the same corpus, and asserts identical result sets and order. This is the
graph counterpart of `VespaParityIT`.

### 7.4 Reference battery

These are the queries the plan's parity and latency gates run. They double as documentation.

| # | Question | Shape |
|---|---|---|
| T1 | Who follows X? (count, then page) | `Users[X]` → `In{tags:p, kinds:3}` → `Author` |
| T2 | Follows-of-follows not already followed, ranked by how many of my follows follow them | 4 steps + `exclude` + `PATHS` |
| T3 | The full reply tree under root R, depth ≤ 5 | `Events[R]` → (`In{roles:root,reply}`)×5 |
| T4 | Notes my follows reacted to this week, ranked by reactions | follows → `Authored{7}` → `Out{roles:reaction}` + `PATHS` |
| T5 | Notes zapped by my follows (example above) | §7.2 |
| T6 | Long-form articles quoted by kind-1 notes of my follows | follows → `Authored{1}` → `Out{roles:quote}` → where kind 30023 |
| T7 | Users awarded badge B who also wear it | `Addresses[B]` → `In{kinds:8}` → `Out{roles:awarded}` ∩ `In{kinds:10008,30008}` → `Author` |
| T8 | Approved posts of community C and their authors | `Addresses[C]` → `In{kinds:4550}` → `Out{roles:approve}` → `Author` |
| T9 | Everyone NIP-85 provider S asserts about, and who listed S | `Users[S]` → `Authored{30382}` → `Out{roles:asserts}`; `Users[S]` → `In{kinds:10040}` → `Author` |
| T10 | Referenced-but-missing events most cited by my follows | … → `Out{tags:e}` → `where{stored:false}` + `PATHS` |
| T11 | Hashtag co-occurrence: tags used alongside #bitcoin in the last day | `Tags[t,bitcoin]` → `In{kinds:1, since}` → `Out{tags:t}` + `PATHS` |
| T12 | Who reported user X, and do I follow any of them? | `Users[X]` → `In{roles:report}` → `Author` ∩ follows |

---

## 8. Store facade and query surfaces (`:store`)

### 8.1 Facade

```kotlin
object Neo4jEventStore {
    fun open(
        url: String = "bolt://localhost:7687",
        user: String = "neo4j",
        password: String,
        database: String = "neo4j",
        relay: NormalizedRelayUrl? = null,          // MUST equal the Vespa store's in a synced deployment (NIP-62 scope)
        installSchema: Boolean = true,              // constraints/indexes/:Meta + migrations, idempotent
        writers: WriterTopology = WriterTopology.SHARED_STRICT,
        bodies: BodyMode = BodyMode.FULL,           // FULL | SKELETON, see §10
        kindRegistry: KindRegistry = KindRegistry.quartzKnownKinds(),
    ): Neo4jEventStore
}

class Neo4jEventStore internal constructor(...) : IEventStore by store {
    val store: NostrSemanticsStore      // escape hatch, as in vespa-eventstore
    val graph: GraphReads               // traverse / degree / edgesOf / explain / cypher — read-only
    fun metrics(): …; fun backgroundStatus(): …
    suspend fun sweepOrphans(): Long
}
```

The assembled stack is:

- `NostrSemanticsStore(MeteredEventIndex(ledger, neo4j), relay, writers = …)`
- `GraphReads(MeteredGraphIndex(ledger, neo4j))`

`writers` defaults to `SHARED_STRICT` because in vespa-relay two processes (relay and sync) both
write. This is the same reason vespa-relay sets `STORE_WRITERS`.

`mirror/` holds the generic replication pieces of §9. They only need two `IEventStore`s, so they
are unit-tested with two `NostrSemanticsStore(InMemoryEventIndex())` instances with fault
injection:

- `StoreMirror(primary, replica, kindFilter)`
- `MirrorReconciler(primary, replica)`

### 8.2 The Cypher endpoint

The traversal language (§7) stays: it is compact, cacheable, and the
only shape that can later ride the Nostr wire. Cypher is the second, more powerful surface.
Examples of what it adds:

- "the 50 most-quoted articles of the week, grouped by author";
- `shortestPath` between two pubkeys over `p_3`;
- "hashtags whose usage doubled".

`:store` owns the mechanism (`GraphReads.cypher`). vespa-relay owns the HTTP route and who may
call it (plan R6).

```kotlin
suspend fun cypher(
    query: String,
    params: Map<String, Any?> = emptyMap(),
): CypherResult                           // columns, rows, elapsedMs, rejected?(reason)
```

#### 8.2.1 Why it needs its own guard, not just a read-only flag

Neo4j **Community has no role-based access control.** The one account the store connects with
can do anything: write, create users, call procedures, read files through `LOAD CSV`. Nothing
on the server side distinguishes "the mirror writing" from "a stranger's query". Every
protection therefore has to be layered in front of and around the query. Each layer below is
tested against a hostile battery (§8.2.5), and no single layer is trusted alone.

#### 8.2.2 Guard layers (`CypherGuard`)

1. **Pre-flight `EXPLAIN`, then inspect the plan, not the text.**
   - The statement is first run as `EXPLAIN <query>` in a read transaction, so nothing executes.
   - It is rejected unless the summary's `queryType()` is `READ_ONLY`. That rejects `CREATE`,
     `MERGE`, `SET`, `DELETE`, `CALL {…} IN TRANSACTIONS`, schema commands and admin commands.
   - It is also rejected if any plan operator is `LoadCSV`, which blocks both local file reads
     and SSRF through `http://` URLs.
   - It is also rejected if any plan operator is a `ProcedureCall` / function call not on a
     short allowlist (`db.labels`, `db.relationshipTypes`, `db.propertyKeys`, `db.schema.*`).
   - It is also rejected if any plan operator is a `Show*` / `Terminate*` command.
     `SHOW TRANSACTIONS` would reveal other callers' query text, and in Community every caller
     is the same user.
   - Inspecting the plan is robust to comments, casing and unicode tricks that defeat
     text-matching.
2. **Execute in a read transaction** (`AccessMode.READ` + `executeRead`), pinned to the data
   database. This is belt and braces behind layer 1: a write that somehow planned as read-only
   still fails at execution.
3. **Server configuration**, applied by the relay's compose file and asserted by
   `SchemaInstaller` at boot (it refuses to open if unsafe):
   - `dbms.security.procedures.allowlist` is the same short list;
   - no APOC / GDS plugins in the serving instance;
   - `dbms.security.allow_csv_import_from_file_urls=false`, with no import directory.
4. **Resource limits: none in v1.** There are no timeouts, row or byte caps, memory caps,
   concurrency caps, rate limits or load shedding. A query runs to completion, and one heavy
   query can slow the mirror's writes. Limits are added later, from production measurements
   (plan P7). Access control (Q7) is the only thing that decides who can run a query.
5. **Audit.** Every call is logged to the relay's audit directory: caller, a query hash, the
   parameter *names*, elapsed time, rows, and the outcome or rejection reason. Hashing the text
   plus logging on rejection is enough to reproduce abuse without storing every query verbatim.

**Parameters:** `$params` are passed natively, and callers are expected to use them. There is no
string interpolation anywhere in the path.

#### 8.2.3 Results

Rows are streamed as JSON (`{"columns": [...], "rows": [[...]], "elapsedMs": n}`).
Graph values serialize by label:

- an `:Event:Stored` node is the **NIP-01 event JSON**. Under `BodyMode.SKELETON` it is hydrated
  from Vespa by id, the same path as traversals.
- a stub `:Event` is `{"id": …, "stored": false}`.
- `:User` is `{"pubkey"}`, `:Address` is `{"address", "kind", "pubkey", "d"}`, and `:Tag` is
  `{"name", "value"}`.
- a relationship is `{"type", "start", "end", …properties}`.
- a path is an alternating list.
- scalars are native JSON. A 64-bit integer outside ±2^53 is a string.

#### 8.2.4 What callers can see

The endpoint sees **everything the store holds, ungated.** The relay's observer lens / trust
floor (`LensRequiredPolicy`, the NIP-85 gate) is a REQ-path concept and does not apply. Every
Cypher read is the equivalent of `include:spam`.

That is acceptable for public notes. It is **not obviously acceptable for DM metadata.**

- Kind-4 DMs carry sender + recipient in the clear.
- Kind-1059 gift wraps carry the recipient `p`.
- Bulk graph queries turn those into "who messages whom" graphs far more cheaply than paging
  REQs.

**Decision D3 (recommended): the relay's mirror does not replicate DM-metadata kinds** (4,
1059, 21059 and any future sealed-DM wrapper) into Neo4j. The reconciler applies the same kind
filter.

- In the relay deployment, the Neo4j `IEventStore` is complete for everything except those
  kinds. That is fine, because it does not serve REQs there (§1).
- A standalone Neo4j store still stores them, and the parity gates still cover them.
- The exclusion is `StoreMirror(kindFilter = …)` config, not schema, so an operator can
  choose otherwise. This is Q7 in §13.

#### 8.2.5 Hostile-query battery (`CypherGuardIT`)

Every case must be **rejected before execution, or fail inside Neo4j without side effects**. The
test asserts the database is byte-for-byte unchanged afterwards (node/relationship counts,
`:Meta`, users).

- Writes:
  - `CREATE` / `MERGE` / `SET` / `REMOVE` / `DELETE` / `DETACH DELETE`;
  - `FOREACH` writes;
  - `CALL {…} IN TRANSACTIONS`.
- Schema and admin commands:
  - `CREATE INDEX` / `CONSTRAINT`;
  - `CREATE USER`, `ALTER USER`, `SHOW USERS`;
  - `SHOW TRANSACTIONS`, `TERMINATE TRANSACTIONS`;
  - `STOP DATABASE`, `USE system …`.
- Procedures and file access:
  - `LOAD CSV FROM 'file:///etc/passwd'` and `LOAD CSV FROM 'http://169.254.169.254/…'`;
  - `CALL dbms.*`, `CALL db.createLabel`;
  - a procedure absent from the allowlist;
  - `apoc.*` (absent).
- Evasion attempts: comments, mixed case, unicode escapes, and a multi-statement payload.

#### 8.2.6 The schema as a public contract

Once strangers write Cypher against it, node labels, relationship type names, property names
and role strings are API.

- `docs/schema.md` is the reference. It contains every label, type family, property and role,
  with example queries: T1–T12 written in Cypher.
- `GET /graph/schema` serves the live view:
  - `:Meta.schema_version`;
  - labels and relationship types present, with counts;
  - the role table;
  - the kind registry.
- **Versioning.** Additive changes bump the minor version, e.g. a new role, or a kind promoted
  out of `_other`. A promotion is additive but visible: `p_other {kind: 1234}` edges become
  `p_1234`, so queries on `_other` should also filter by `kind`. Renames and removals bump the
  major version, are announced, and ship with a migration note.
- `explain(traversal)` returns the Cypher a traversal compiles to. The traversal language
  doubles as a way to learn the schema.

---

## 9. Staying in sync with vespa-eventstore (in vespa-relay)

vespa-relay has **no single write choke point**. The relay process and the sync process each
open a store against the same Vespa, and writes arrive from several call sites:

- `IngestQueue` (client EVENTs);
- `IngestPipeline` (mirrored streams);
- `RelayVerdictRecord`;
- `RelayProfile`;
- NIP-86 `purge`;
- `RetractionAudit.deleteMissing`;
- `ExpirationSweeper`.

The only common surface is the `IEventStore` each process hands out.

Cascading removals (supersession, kind-5 targets, vanish) happen *inside* the Vespa store, and
the library exposes no hook for them. D1 makes that irrelevant: Neo4j does not need Vespa's
removals, because it computes the same ones from the same events.

**Decision D2 — Vespa is the authority. Neo4j is a replica fed with Vespa's *accepted events*,
repaired by set reconciliation.** There are three mechanisms, in order of latency:

1. **Live mirror (`StoreMirror`).** This is an `IEventStore` decorator installed in both
   processes, over the `VespaEventStore`.
   - `kindFilter` drops the kinds D3 excludes (§8.2.4) before queueing; the reconciler applies
     the same filter to both sides.
   - For every `insert` / `batchInsert` / `transaction` outcome that is `Accepted` in Vespa, the
     event is queued to a bounded in-memory queue. A single consumer `batchInsert`s it into
     Neo4j.
   - `delete(filter)` (NIP-86 purge, `deleteMissing`) is forwarded **as an operation**, in order
     with inserts.
   - `deleteExpiredEvents()` is **not** forwarded. Each store sweeps its own clock, and the rule
     is deterministic.
   - Vespa's latency and outcome are what the caller sees. **Neo4j is never on the client's
     `OK` path.**
   - If Neo4j is slow or down, the queue fills. Overflow drops the event and marks its
     `created_at` hour **dirty** for the reconciler. It never blocks Vespa ingest.
   - Replica rejections that Vespa accepted (`replaced:`, `deleted:`, …) are normal when the
     queue reorders a pair. The final state converges, so they are counted and not alarmed on.
     An `insert-failed` / `Failed` marks the hour dirty.
2. **Windowed reconciliation (`MirrorReconciler`).** This runs in the relay process as a
   maintenance job, beside `ExpirationSweeper`.
   - For a `created_at` window `[a, b]`, it takes the `(created_at, id)` snapshots of both stores
     through `snapshotIdsForNegentropy` with **no observer context**, so the Vespa side is
     lens-free.
   - It merges the two sorted lists:
     - **missing** in Neo4j → `query(Filter(ids = chunk))` from Vespa, in chunks of 500, then
       `batchInsert` into Neo4j;
     - **extra** in Neo4j → `delete(Filter(ids = chunk))` on Neo4j. This covers Vespa-only
       removals such as `sweepOrphanScores`, and crash windows.
   - Cadence:
     - dirty hours first;
     - then a rolling "recent" pass (the last 2 h, every 5 min);
     - then a full sweep that walks the corpus in windows sized to about 250k ids, resumable
       with a cursor file (the `FtsReindex` pattern).
   - Races are benign:
     - an event still in the live queue gets inserted twice, and the second insert is
       `duplicate:`;
     - an extra removed ahead of its kind-5 converges when the kind-5 lands.
3. **Backfill.** An empty Neo4j converges by (2) alone: a full sweep inserts everything. That is
   correct but bounded by Cypher ingest throughput. For the 212M-event staging corpus the plan
   adds a **bulk path**:
   - dump Vespa (a paged full-document walk; this needs `EngineReads.visitDocsPage` exposed in
     vespa-eventstore, plan §V1);
   - write node/relationship CSVs with the **same `EdgeDeriver`**;
   - `neo4j-admin database import full`;
   - then run (2) from the dump's start time to catch up.

   The dump is Vespa's post-policy state, so importing it verbatim is exact.

**Health surface.** The relay's `/stats.json` and pulse page report:

- `graph.queue.depth`
- `graph.queue.dropped`
- `graph.dirtyHours`
- `graph.reconcile.{lastRun, missing, extra, cursor}`
- `graph.replicaRejected{reason}`
- `graph.lagSeconds` (the age of the oldest queued event)

A configured mirror that is not draining is a reported fault, never silently inert (vespa-relay
convention 4).

**Correctness argument, stated plainly.** Both stores run the same `NostrSemanticsStore` over
different engines. The rules are order-independent in their final state. Neo4j only ever
receives events Vespa accepted, plus Vespa's own filter deletes. Anything else Vespa removes, or
anything Neo4j missed, is caught by the reconciler, which treats Vespa's id set as the truth.
The residual divergence window is therefore bounded by the recent-pass cadence for live traffic,
and by the full-sweep period for old data.

---

## 10. Capacity and body storage

Staging holds 211.8M events (132.4M kind 1), measured 2026-08-14. The order-of-magnitude
estimate below is **to be measured in plan Phase 7**, not trusted:

| | Estimate | Basis |
|---|---|---|
| Nodes | ~250M | Events, plus ~10–20M users/addresses/tags/stubs |
| Relationships | 1.5–2.5B | kind 1 ≈ 2–3 refs each; kind 7 ≈ 2; current kind-3 lists ≈ millions × a few hundred `p` each |
| Graph store | 150–250 GB | Neo4j block format, with relationship properties |
| Bodies (`content` + `sig` + `tags`) | +100–150 GB | ~600 B average event |

Two consequences drive options:

- **`BodyMode.SKELETON`** stores `tags` (the policy needs d / expiration / deletion coordinates)
  but drops `content` and `sig`.
  - Traversals return ids, and the relay **hydrates from Vespa** (`query(Filter(ids))`), which
    it already has.
  - It roughly halves the disk.
  - Such a store must not serve REQs. Its `query` returns events without content or signature,
    and it refuses to start if asked to serve them.
  - Parity gates run in `FULL`; the relay deployment is expected to run `SKELETON`. **Open
    question Q2.**
- **The page cache should hold the relationship and node stores.** On the current single box
  (47 GiB, already shared with Vespa at 34g plus relay and sync), that is not possible at full
  scale. The deployment decision is a separate Neo4j host, or a trimmed kind set (Q3).

---

## 11. Licensing

| Component | License | Status |
|---|---|---|
| `org.neo4j.driver:neo4j-java-driver` 6.3.0 | Apache-2.0 (verified in `neo4j-java-driver-parent-6.3.0.pom`) | OK, linked |
| `org.testcontainers:neo4j` | MIT | OK, test-only |
| Neo4j Community Server 2026.x | **GPLv3** | Run as a separate process over Bolt only. **Never embed** (`org.neo4j:neo4j` in-process would put the jar under GPL linking terms). |
| Neo4j Enterprise | Commercial | Not required. Clustering and online backup are the only features we would miss (Q3). |

---

## 12. Testing

The testing model mirrors vespa-eventstore.

| Gate | Needs | Asserts |
|---|---|---|
| `./gradlew build` (unit) | Nothing: no Docker, no Neo4j | Deriver golden tests: one per appendix row, plus link rules, **including the nsec test**. Role table. `EventQuery`→Cypher and `Traversal`→Cypher compile snapshots. `NostrSemanticsStore` over `InMemoryGraphIndex`. `InMemoryGraphIndex` traversal semantics. Mirror/reconciler convergence under reordering, drops and crashes. `ModuleBoundariesTest`, `PortDecoratorsTest`. |
| `spotlessCheck` | Nothing | ktlint plus the MIT header (`.spotless/copyright.kt`) |
| `:benchmark:test -Pintegration` | Docker (testcontainers `neo4j:2026.09-community`, self-skips without Docker) | `Neo4jParityIT`: vespa-eventstore's `ParityCheck` battery vs Quartz SQLite. The harness is copied, because vespa's `:benchmark` is unpublished. `FilterMatrixIT` (NIP-01 part): `Filter.match` oracle. `TraversalParityIT`: T1–T12 in-memory vs Neo4j. `SchemaMigrationIT`: kind-registry migration. `StagingCorpusIT`: the captured staging export. |
| `CypherGuardIT` (integration) | Docker | The hostile-query battery of §8.2.5: every case is rejected or fails without side effects. `SchemaInstaller` refuses to open on an unsafe server config. |
| vespa-relay `GraphMirrorIT` | Docker (Vespa + Neo4j) | Feed through `StoreMirror` with injected drops. After one reconcile, the id sets are equal, and a kind-5 / vanish / supersession applied only live converges. |

CI (`.github/workflows/build.yml`) runs three jobs, `lint`, `build` and `integration`, as in
vespa-eventstore.

---

## 13. Open questions (decisions for the maintainer)

| # | Question | Recommendation |
|---|---|---|
| Q1 | D1: reuse `NostrSemanticsStore` via the `EventIndex` port, or fork the policy? | **Reuse.** Sync correctness rides on identical rules. |
| Q2 | Body mode in the relay deployment | **`SKELETON`** and hydrate from Vespa. `FULL` for standalone use and gates. |
| Q3 | Hosting at 212M events: the same box, a separate host, or a kind subset? | A separate host with enough RAM for the page cache. Revisit after the Phase 7 measurement. |
| Q4 | How clients reach traversals: HTTP JSON only, or also on the Nostr wire? | **v1: `POST /graph`** (JSON), `POST /graph/cypher` (§8.2) and `GET /graph/explain/{id}`. Nostr-wire exposure (a REQ extension, or a NIP-90 DVM) is a later, separate NIP-shaped decision. |
| Q5 | Maven group / package | `com.vitorpamplona.neo4j.eventstore`, or `com.nosfabrica.neo4j.eventstore` for symmetry with its sibling |
| Q7 | Who may call `POST /graph/cypher`: admins, any NIP-98-authenticated pubkey, or anyone? And is DM metadata (kinds 4/1059/21059) excluded from the relay's graph (D3)? | **Start at `admin`** (NIP-98 against `RELAY_ADMIN_PUBKEYS`, the existing `AdminGate`). With no resource limits in v1 (§8.2.2), widen to `auth` (any NIP-98 pubkey) or `public` only after production-derived limits exist. **Exclude DM metadata: yes.** |
| Q6 | Trust-aware traversals (observer lens, rank floor on reached users) | Later (plan Phase 8), by giving Neo4j a `ReputationIndex` so `TrustProjection` can decorate it too |
