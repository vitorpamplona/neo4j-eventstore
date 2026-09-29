# Graph schema reference

**Schema version 1.1** (the live value is in `:Meta.schema_version` and `GET /graph/schema`).

This is the contract for anyone writing Cypher against the graph. Labels, relationship types,
property names and role strings are API:
- **additive** changes bump the minor version: a new role, a new curated value, a new allowlisted
  tag, or a kind promoted out of `_other`;
- **renames and removals** bump the major version, and are announced with migration notes.

The graph is a **projection** of the events the relay's Vespa store holds. It keeps no event
bodies: an `:Event:Stored` node that a query returns is hydrated into the full NIP-01 event
unless the request says `"hydrate": false`.

---

## Nodes

| Label | Key | Properties | Notes |
|---|---|---|---|
| `:Event:Stored` | `id` (64-hex) | `kind`, `created_at`, `d` (addressable kinds), `expires_at` (NIP-40), and curated values (below) | An event the relay holds |
| `:Event` (without `:Stored`) | `id` | — | A **stub**: an id something references that the relay does not hold. Filter with `NOT n:Stored` (e.g. "most-cited missing events"). |
| `:User` | `pubkey` (64-hex) | `name`, `display_name`, `nip05` (from the current kind 0) | Anyone who authored or was referenced |
| `:Address` | `id` = `kind:pubkey:d` | `kind`, `pubkey`, `d` | Every addressable event (30000–39999), plus any address something references (including `10002:<pk>:`-style replaceable addresses). Kinds are 0–65535. A `d` longer than 1024 UTF-8 bytes appears as `sha256:<hex of the d>` (the key must stay indexable); it is still one node per distinct `d`. |
| `:Tag` | `key` = `name:value` | `name`, `value` | Non-reference single-letter tags, for the allowlisted names `t`, `i`, `k`, `l`, `L`, `r`, `g` only. Values of 256 bytes or less. |

**An event's author is an edge, not a property.** Filter by author from the user:
`(:User {pubkey: $pk})<-[:by_1]-(n)`.

**Replaceable events** (0, 3, 10000–19999) have no `:Address` node unless something references
their address. A user's current follow list is `(:User)<-[:by_3]-(list)`, and there is exactly
one.

## Relationships

Every relationship starts at an `:Event:Stored`, except `OWNED_BY`.

| Type | From → to | Meaning |
|---|---|---|
| `by_<k>` | Event → User | Authorship; `<k>` is the event's kind, e.g. `by_1`, `by_3`, `by_30023` |
| `<t>_<k>` | Event → Event / User / Address / Tag | The event (kind `<k>`) has the literal single-letter tag `<t>`. Examples: `p_3` (a follow), `e_7` (a reaction's target), `q_1` (a quote), `E_1111` (a NIP-22 root), `t_1` (a hashtag), `d_30382` (a NIP-85 subject). |
| `ref_<f>_<k>` | Event → Event / User / Address | A **derived** reference of family `e`, `p` or `a` that is not a literal single-letter tag. `via` says where it came from. |
| `VERSION_OF` | Event → Address | The address's current version. There is at most one. |
| `OWNED_BY` | Address → User | The address's pubkey |

- **Case matters**: `e_1111` is a NIP-22 reply and `E_1111` is a NIP-22 root.
- **Kinds the pinned Quartz does not know** share `<t>_other`, `ref_<f>_other` and `by_other`,
  each with a `kind` property. When a Quartz upgrade learns such a kind, its edges move to their
  own type, which is a minor version bump. Queries over `_other` should filter by `kind`.
- **Counts are O(1).** `COUNT { (u)<-[:p_3]-() }` (followers) is read from Neo4j's per-type degree
  store, so it costs the same for an account with one follower or a million.

### Relationship properties

| Property | Where | Meaning |
|---|---|---|
| `roles` | Only where the type leaves the role open (below) | e.g. `["root", "reply"]` |
| `via` | `ref_…` | `content` (a `nostr:` link in the text), `embedded` (an event embedded in the content, e.g. a repost), `description` (the zap request inside a receipt), or the multi-letter tag that carried it (`zap`, `pinned`, `30382:rank`, …). One target reached two ways is two relationships, one per `via` (a 10040 naming one service for `30382:rank` and `30382:followers`). |
| `kind` | `_other` types | The source kind |
| `report` | `p_1984`, `e_1984`, `a_1984` | The report's CATEGORY, as Quartz reads it: `spam`, `impersonation`, `illegal`, `malware`, `nudity`, `profanity`, `harassment`, `violence` or `other`. Localized labels fold in (`Spam 📣` is `spam`); a type Quartz does not know is `other`. |
| `report_raw` | `p_1984`, `e_1984`, `a_1984` | The type AS WRITTEN, trimmed and lowercased, up to 64 bytes (`swearing`, `ai-generated`, `spam 📣`). Absent when the report wrote none. |
| `scope` | `p_1984` | What the report is about: `user` (it names no event, address or blob, so it is a standing complaint about the person), or `event`, `address`, `blob` (it reports that content, and this `p` is its author). `address` wins when a report names a version and its address. |
| `rank`, `followers` | `d_30382` | The NIP-85 assertion's scores for that user |

### Roles

**Stored roles** apply where the type admits more than one role. Test them with
`'reply' IN r.roles`.

| Types | Roles |
|---|---|
| `e_<k>` for kinds 1, 42, 1311, 2004, 30818, 1617, 1630–1633 | `root` (the thread root); `reply` (the **direct parent**: the reply-marked `e`, or the root when there is none, as Quartz's `replyingTo()` reads it); `mention`; `fork`. A direct reply to the root has `["root","reply"]` on that one edge. |
| `e_6`, `a_6`, `e_16`, `a_16` | `repost` (the last `e` / `a`) or `context` |
| `e_7`, `a_7` | `reaction` (the last `e` / `a`, NIP-25's target) or `context` |
| `ref_p_9735` with `via: "description"` | `zapper` (the zap sender, from the embedded request) |

**Implied roles** are not stored; the type says them:

| Type | Role |
|---|---|
| `p_1`, `p_42`, … (threaded kinds) | mention |
| `E_1111` / `A_1111`, `e_1111` / `a_1111` | root / reply |
| `P_1111`, `p_1111` | root_author / reply_author |
| `q_<any>` | quote |
| `p_6`, `p_16` | reposted_author |
| `p_7` | reacted_author |
| `e_9734` / `e_9735` / `a_9734` / `a_9735` | zap |
| `p_9734`, `p_9735` | zapped (the recipient) |
| `P_9735` | zapper |
| `e_5`, `a_5` | delete |
| `e_1984`, `a_1984`, `p_1984` | report |
| `e_1985`, `a_1985`, `p_1985` | label |
| `p_3` | follow |
| `p_10000` | mute |
| `p_30000`, `p_39089`, `p_39092`, `p_10017`, `p_10020`, `p_10101` | member |
| `e_10001` | pin |
| `e_10003` / `a_10003`, and the NIP-51 bookmark and curation sets | bookmark |
| `a_10004`, `a_34550`, `a_4550` | community |
| `p_34550` | moderator |
| `e_4550` | approve |
| `a_8`, `a_30008`, `a_10008` | badge |
| `p_8` | awarded |
| `e_30008`, `e_10008` | award |
| `d_30382`, `d_30383`, `d_30384` | asserts (the NIP-85 subject) |
| `e_41` | channel |
| `e_43` | hide |
| `p_44` | mute |

### Curated values on nodes

| Where | Property | From |
|---|---|---|
| `:User` | `name`, `display_name`, `nip05` | The user's current kind 0 |
| kind-7 `:Event` | `content` | The reaction symbol (`+`, `-`, an emoji, `:shortcode:`), up to 32 bytes |
| kind-9735 `:Event` | `msats` | The receipt's bolt11 amount |
| kind-9734 `:Event` | `msats` | The request's `amount` tag |
| kind-0 `:Event` | `name`, `display_name`, `nip05` | The same names it sets on its author (so they survive the author's other kind 0 being removed) |
| kinds 30023, 30311, 34550 | `title` | The `title` tag (or `name` for a community) |

---

Amounts above 21M BTC (2.1×10¹⁸ msats) are not stored, so `sum(z.msats)` over real zaps cannot
overflow.

### Reports (NIP-56), in one place

A report reaches its subjects through `p_1984`, `e_1984` and `a_1984`. Three questions come up in
nearly every report query, and each is a property of the edge, so it needs no second hop:
- **About the person, or about their content?** `p_1984.scope`. A report of a note tags the note
  (`e`) and its author (`p`). Only `scope: 'user'` is a complaint about the person themselves.
- **Which category?** `report`, from a fixed vocabulary. Filter on it to keep the categories that
  matter and drop the minor types clients invent, which all land in `other`.
- **Which exact type?** `report_raw`, the text the client wrote.

Relationship indexes cover `p_1984 (scope, report)`, `p_1984 (report_raw)`, `e_1984 (report)` and
`a_1984 (report)`, so a report query that is not anchored on one user still seeks rather than
scans.

### Internal labels

`:Removed` (a short-lived fence of recently removed ids, swept after about two hours) and `:Meta`
(the schema singleton) belong to the projection's bookkeeping. They are not part of this contract.

## Example queries

These are run by `ReferenceQueriesIT` against a graph with known answers. Parameters are passed
in the request's `params`.

```cypher
// T1 — follower count, and the followers
MATCH (u:User {pubkey: $pk}) RETURN COUNT { (u)<-[:p_3]-() } AS followers;
MATCH (:User {pubkey: $pk})<-[:p_3]-(:Event)-[:by_3]->(f:User) RETURN f;

// T2 — follows-of-follows I don't follow, ranked by how many of my follows follow them
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)<-[:by_3]-(:Event)-[:p_3]->(fof:User)
WHERE fof <> me AND NOT EXISTS { (me)<-[:by_3]-(:Event)-[:p_3]->(fof) }
RETURN fof, count(DISTINCT f) AS via ORDER BY via DESC LIMIT 50;

// T3 — every note in a thread (every NIP-10 reply tags the root)
MATCH (root:Event {id: $id})<-[r:e_1]-(n:Stored) WHERE 'root' IN r.roles
RETURN n ORDER BY n.created_at;

// T3b — the reply TREE, any depth, through direct-parent edges
MATCH (root:Event {id: $id}) ((p)<-[r:e_1]-(c:Stored) WHERE 'reply' IN r.roles)+ (leaf)
RETURN leaf;

// T4 — notes my follows reacted to this week, by how many of them
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)<-[:by_7]-(r:Stored)-[x:e_7]->(n:Stored)
WHERE r.created_at >= $since AND 'reaction' IN x.roles
RETURN n, count(DISTINCT f) AS reactors ORDER BY reactors DESC LIMIT 50;

// T5 — notes my follows zapped this week, by total sats
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)
MATCH (f)<-[:P_9735|ref_p_9735]-(z:Stored)-[:e_9735]->(n:Stored {kind: 1})
WHERE z.created_at >= $since
RETURN n, sum(z.msats) AS msats, count(DISTINCT f) AS zappers ORDER BY msats DESC LIMIT 50;

// T6 — articles quoted by my follows' notes
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)<-[:by_1]-(:Stored)-[:q_1]->(a:Address)<-[:VERSION_OF]-(article:Stored)
RETURN article, count(*) AS quotes ORDER BY quotes DESC LIMIT 50;

// T7 — users awarded badge B who also wear it
MATCH (b:Address {id: $badge})<-[:a_8]-(:Stored)-[:p_8]->(u:User)
WHERE EXISTS { (u)<-[:by_30008|by_10008]-(:Stored)-[:a_30008|a_10008]->(b) }
RETURN u;

// T8 — a community's approved posts and their authors
MATCH (c:Address {id: $community})<-[:a_4550]-(:Stored)-[:e_4550]->(post:Stored)-[b]->(author:User)
WHERE type(b) STARTS WITH 'by_'
RETURN post, author;

// T9 — who NIP-85 service S ranks >= 80
MATCH (:User {pubkey: $service})<-[:by_30382]-(:Event)-[a:d_30382]->(u:User)
WHERE a.rank >= 80
RETURN u, a.rank ORDER BY a.rank DESC;

// T10 — events my follows cite that the relay does not hold
MATCH (me:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(f:User)<-[b]-(n:Stored)-[r]->(missing:Event)
WHERE type(b) STARTS WITH 'by_' AND NOT missing:Stored AND (type(r) STARTS WITH 'e_' OR type(r) STARTS WITH 'q_')
RETURN missing.id, count(*) AS citations ORDER BY citations DESC LIMIT 50;

// T11 — hashtags used alongside #bitcoin in the last day
MATCH (:Tag {key: 't:bitcoin'})<-[:t_1]-(n:Stored)-[:t_1]->(o:Tag)
WHERE n.created_at >= $since AND o.key <> 't:bitcoin'
RETURN o.value, count(*) AS uses ORDER BY uses DESC LIMIT 20;

// T12 — who reported X as a PERSON (not one of X's notes), among the people I follow
MATCH (:User {pubkey: $x})<-[r:p_1984 {scope: 'user'}]-(:Stored)-[:by_1984]->(reporter:User)
WHERE EXISTS { (:User {pubkey: $me})<-[:by_3]-(:Event)-[:p_3]->(reporter) }
RETURN reporter, r.report;

// T13 — users with reports that count: the standard categories only, so types clients
// invented for minor things (`swearing` is category `other`) drop out
MATCH (u:User)<-[r:p_1984]-(rep:Stored)
WHERE r.report IN ['impersonation', 'spam', 'illegal', 'malware'] AND rep.created_at >= $since
RETURN u, r.scope AS scope, count(*) AS reports ORDER BY reports DESC LIMIT 50;

// T14 — one invented type, by its text
MATCH (:Stored)-[r:e_1984 {report_raw: 'swearing'}]->(n:Stored) RETURN n;

// Hybrid — full-text search in Vespa first (a NIP-50 REQ), then the graph
UNWIND $ids AS id
MATCH (n:Event:Stored {id: id})<-[:e_7]-(:Event)-[:by_7]->(r:User)
RETURN n, count(DISTINCT r) AS reactors ORDER BY reactors DESC;
```

## What the endpoint refuses

Queries must be read-only. A query is refused **before it runs** if its plan:
- writes;
- touches schema;
- runs an admin command;
- uses `LOAD CSV`;
- lists or terminates transactions (`SHOW …` / `TERMINATE …`);
- calls a procedure other than `db.labels`, `db.relationshipTypes`, `db.propertyKeys`, or
  `db.schema.*`.

There are no resource limits in v1. Write queries that bound themselves: anchor on a key and
`LIMIT` the result.
