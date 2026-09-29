# Graph schema reference

**Schema version 2.0** (the live value is in `:Meta.schema_version` and `GET /graph/schema`).

This is the contract for anyone writing Cypher against the graph. Labels, relationship types and
property names are API:
- **additive** changes bump the minor version: a new relation, a new props field, a newly mapped
  kind, a new curated value;
- **renames and removals** bump the major version, and are announced with migration notes.

The graph is a **projection** of the events the relay's Vespa store holds. It keeps no event
bodies: an `:Event:Stored` node that a query returns is hydrated into the full NIP-01 event
unless the request says `"hydrate": false`.

**2.0 in one line:** a relationship's type says what its target IS to the event — `ROOT`,
`PARENT`, `REACTED`, `FOLLOW`, `REPORTED_USER` — instead of which tag carried it (1.x's `e_1`,
`p_3`, `p_1984`). What each kind's references mean is decided once, per Quartz event class, in
the [link vocabulary](vocabulary.md).

---

## Nodes

| Label | Key | Properties | Notes |
|---|---|---|---|
| `:Event:Stored` | `id` (64-hex) | `kind`, `created_at`, `d` (addressable kinds), `expires_at` (NIP-40), and curated values (below) | An event the relay holds |
| `:Event` (without `:Stored`) | `id` | — | A **stub**: an id something references that the relay does not hold. Filter with `NOT n:Stored` (e.g. "most-cited missing events"). |
| `:User` | `pubkey` (64-hex) | `name`, `display_name`, `nip05` (from the current kind 0) | Anyone who authored or was referenced |
| `:Address` | `id` = `kind:pubkey:d` | `kind`, `pubkey`, `d` | Every replaceable (`3:<pk>:`, `10002:<pk>:`) and addressable (`30023:<pk>:<d>`) event's own address, plus any address something references. Kinds are 0–65535. A `d` longer than 1024 UTF-8 bytes appears as `sha256:<hex of the d>` (the key must stay indexable); it is still one node per distinct `d`. |
| `:Tag` | `key` = `name:value` | `name`, `value` | A value a kind links that is not an event, user or address: a hashtag (`t:nostr`), a URL (`r:https://…`), an external id (`i:isbn:…`), a group (`h:<id>`), a kind (`k:1`). `name` is the tag it was written in. Values of 256 bytes or less. |

**An event's author is an edge, not a property.** Filter by author from the user:
`(:User {pubkey: $pk})<-[:AUTHOR]-(n:Stored)`.

**Replaceable and addressable events hang off their address.** Each version points at its own
`:Address` through `ADDRESS`, and the projection keeps exactly one held version per address. A
user's current follow list is one index seek away:
`(:Address {id: '3:' + $pk + ':'})<-[:ADDRESS]-(list:Stored)`. Prefer that anchor over
`(u)<-[:AUTHOR]-(:Stored {kind: 3})`, which walks everything the user ever signed.

## Relationships

Every relationship starts at an `:Event:Stored`, except an address's `AUTHOR` (its pubkey).

The **type** is a relation of the [vocabulary](vocabulary.md): what the target is to the event
that states it. One relation per role across kinds: `PARENT` is a note reply's parent, a NIP-22
comment's parent item and a git reply's; the source node's `kind` says which, and a query that
cares filters on it (`(c:Stored {kind: 1111})-[:PARENT]->(x)`). The full catalogue — 172
relations, each with its targets, meaning and kinds — is in [`vocabulary.md`](vocabulary.md#the-vocabulary),
and `GET /graph/schema` lists them. The ones most queries start from:

| Relation | From → to | Meaning |
|---|---|---|
| `AUTHOR` | Event → User; Address → User | The signer; an address's pubkey |
| `ADDRESS` | Event → Address | A replaceable / addressable event's own address (at most one held version per address) |
| `ROOT`, `PARENT` | Event → Event / Address / Tag | The thread root and the direct parent (NIP-10 notes, NIP-22 comments and their `I` scopes, chat, git, …). A direct reply to the root has both. |
| `ROOT_AUTHOR`, `PARENT_AUTHOR` | Event → User | Their authors, where the event names them (a `p` on a note is `PARENT_AUTHOR` only when it is the parent's author) |
| `MENTION`, `QUOTE` | Event → Event / User / Address | A reference without a structural role (a tag, or a `nostr:` URI in the content); a NIP-18 `q` |
| `REACTED`, `REACTED_AUTHOR` | Event → Event / Address; → User | A reaction's target (NIP-25: the LAST `e`/`a`) and its author (the last `p`) |
| `REPOSTED`, `REPOSTED_AUTHOR` | Event → Event / Address; → User | A repost's original and its author |
| `ZAPPED`, `ZAP_RECIPIENT`, `ZAP_SENDER` | Event → Event / Address; → User | A zap request's / receipt's content, recipient and (receipt `P`) sender |
| `FOLLOW` | Event → User | Kind 3 only: the social graph. Other follow-like lists are `SUBSCRIBED`. |
| `MUTE`, `BOOKMARK`, `PIN`, `MEMBER` | Event → … | NIP-51 lists name their entries as the list does |
| `REPORTED_USER`, `REPORTED`, `REPORTED_AUTHOR` | Event → User; → Event / Address / Tag; → User | NIP-56: a complaint about the PERSON; the reported content; the author of reported content |
| `DELETED` | Event → Event / Address | NIP-09 |
| `LABELED` | Event → … | NIP-32 |
| `SUBJECT` | Event → User / Event / Address / Tag | A NIP-85 assertion's subject (its `d`), with the scores |
| `SERVICE_PROVIDER` | Event → User | A 10040's trust services (`via` names the metric, e.g. `30382:rank`); a NIP-90 request's DVMs |
| `HASHTAG`, `TAG` | Event → Tag | A `t` (lowercased), and the other value tags a kind opts into (`i`, `k`, `r`, `g`, …) |
| `GROUP`, `COMMUNITY` | Event → Tag; → Address | A NIP-29 / Marmot / Buzz group (its `h`); a NIP-72 community |
| `CLIENT`, `ZAP_SPLIT`, `EMOJI_SET` | Event → Address; → User; → Address | Tags any event may carry: NIP-89 `client`, NIP-57 zap splits, a NIP-30 emoji's set |

- **Counts are O(1).** `COUNT { (u)<-[:FOLLOW]-() }` (followers) is read from Neo4j's per-type
  degree store, so it costs the same for an account with one follower or a million. That is why
  the vocabulary splits a relation wherever queries separate its meanings on one target type:
  counting a relation is constant-time, filtering on a property reads every edge.
- **No fallback.** A kind the pinned Quartz does not know states only `AUTHOR`, `ADDRESS` and the
  every-kind tags: its other tags mean nothing the projection can vouch for.

### Relationship properties

Every link a tag or the content states carries **`via`**: the tag name (`e`, `p`, `a`, `q`,
`30382:rank`, …) or `content` for a NIP-27 `nostr:` URI in the text. One target reached two ways
is two relationships (a 10040 naming one service for `30382:rank` and `30382:followers`).

The relations below carry **typed props** (`vocab/props`; absent values are left out):

| Relations | Properties |
|---|---|
| `ACTOR` | `path` (string) |
| `AUCTION` | `amount` (float) |
| `AUDITED` | `action` (string) |
| `BID` | `status` (string), `duration_extension` (integer) |
| `OPPONENT`, `WINNER` | `result` (string), `termination` (string) |
| `COLLABORATED`, `COLLABORATED_AUTHOR` | `roles` (list), `status` (string) |
| `CREDITED` | `credit` (string) |
| `FOUND` | `verified` (boolean) |
| `RECIPIENT`, `AGENT` | `frame` (string) |
| `ITEM` | `polarity` (float) |
| `LABELED` | `labels` (list) |
| `MEMBER` | `roles` (list), `order` (integer), `level` (integer), `title` (string), `score` (integer) |
| `ARCHIVED`, `BANNED`, `TIMED_OUT`, `UNARCHIVED` | `reason` (string), `expiration` (integer) |
| `MUTE` | `muted_kind` (integer) |
| `CURATED`, `PIN` | `order` (integer) |
| `OWNER` | `conditions` (string) |
| `PARTICIPANT` | `roles` (list), `proof` (string) |
| `RECOMMENDED` | `platform` (string) |
| `POLL` | `responses` (list) |
| `TAGGED` | `x` (integer), `y` (integer) |
| `RATED`, `RATED_AUTHOR` | `mark` (string), `stars` (float) |
| `SITE_MANIFEST` | `release` (string) |
| `REPORTED_USER`, `REPORTED`, `REPORTED_AUTHOR` | `report` (string), `report_raw` (string) |
| `RESOLVED` | `status` (string), `action` (string), `reason` (string) |
| `HIGHLIGHTED_AUTHOR`, `ADDED_USER`, `ADMIN`, `ALLOWED`, `PODCAST_AUTHOR`, `ROLE_CHANGED` | `roles` (list) |
| `CALENDAR_EVENT` | `status` (string), `fb` (string) |
| `SERVICE_PROVIDER` | `service` (string) |
| `CONFIRMED`, `REQUEST` | `status` (string) |
| `SUBJECT` | `rank` (integer), `followers` (integer), `hops` (integer), `first_created_at` (integer), `post_cnt` (integer), `reply_cnt` (integer), `reactions_cnt` (integer), `zap_amt_recd` (integer), `zap_amt_sent` (integer), `zap_cnt_recd` (integer), `zap_cnt_sent` (integer), `zap_avg_amt_day_recd` (integer), `zap_avg_amt_day_sent` (integer), `reports_cnt_recd` (integer), `reports_cnt_sent` (integer), `active_hours_start` (integer), `active_hours_end` (integer), `comment_cnt` (integer), `quote_cnt` (integer), `repost_cnt` (integer), `reaction_cnt` (integer), `zap_cnt` (integer), `zap_amount` (integer) |
| `VIEWED` | `phase` (string) |
| `VOTED` | `direction` (string) |
| `WOT_ROOT` | `depth` (integer) |
| `ZAPPED`, `ZAP_RECIPIENT` | `msats` (integer) |
| `ZAP_SPLIT` | `weight` (float) |

`report` is the report's CATEGORY as Quartz reads it (`spam`, `impersonation`, `illegal`,
`malware`, `nudity`, `profanity`, `harassment`, `violence`, or `other` for a type Quartz does not
know; localized labels fold in: `Spam 📣` is `spam`); `report_raw` is the type AS WRITTEN,
trimmed and lowercased (`swearing`, `ai-generated`), absent when the report wrote none. Integers
are Cypher integers; lists are string lists (test membership with `'admin' IN r.roles`).

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

A report reaches its subjects through three relations, and the question every report query
asks — about the person, or about their content? — is the relation itself:
- `REPORTED_USER`: the report names no content, so it is a standing complaint about the person;
- `REPORTED`: the reported event, address or blob;
- `REPORTED_AUTHOR`: the author of reported content (the report's `p` beside its `e` / `a` / `x`).

Each carries `report` and `report_raw`. Relationship indexes cover `REPORTED_USER (report)`,
`REPORTED_USER (report_raw)`, `REPORTED_AUTHOR (report)` and `REPORTED (report)`, so a report
query that is not anchored on one user still seeks rather than scans.

### Internal labels

`:Removed` (a short-lived fence of recently removed ids, swept after about two hours) and `:Meta`
(the schema singleton) belong to the projection's bookkeeping. They are not part of this contract.

## Example queries

These are run by `ReferenceQueriesIT` against a graph with known answers. Parameters are passed
in the request's `params`.

```cypher
// T1 — follower count, and the followers
MATCH (u:User {pubkey: $pk}) RETURN COUNT { (u)<-[:FOLLOW]-() } AS followers;
MATCH (:User {pubkey: $pk})<-[:FOLLOW]-(:Stored)-[:AUTHOR]->(f:User) RETURN f;

// T2 — follows-of-follows I don't follow, ranked by how many of my follows follow them
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(mine:Stored)-[:FOLLOW]->(f:User)
MATCH (:Address {id: '3:' + f.pubkey + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(fof:User)
WHERE fof.pubkey <> $me AND NOT EXISTS { (mine)-[:FOLLOW]->(fof) }
RETURN fof, count(DISTINCT f) AS via ORDER BY via DESC LIMIT 50;

// T3 — every note in a thread (every NIP-10 reply tags the root)
MATCH (root:Event {id: $id})<-[:ROOT]-(n:Stored)
RETURN n ORDER BY n.created_at;

// T3b — the reply TREE, any depth, through direct-parent edges
MATCH (root:Event {id: $id}) ((p)<-[:PARENT]-(c:Stored))+ (leaf)
RETURN leaf;

// T4 — notes my follows reacted to this week, by how many of them
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(f:User)
MATCH (f)<-[:AUTHOR]-(r:Stored {kind: 7})-[:REACTED]->(n:Stored)
WHERE r.created_at >= $since
RETURN n, count(DISTINCT f) AS reactors ORDER BY reactors DESC LIMIT 50;

// T5 — notes my follows zapped this week, by total sats
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(f:User)
MATCH (f)<-[:ZAP_SENDER]-(z:Stored)-[:ZAPPED]->(n:Stored {kind: 1})
WHERE z.created_at >= $since
RETURN n, sum(z.msats) AS msats, count(DISTINCT f) AS zappers ORDER BY msats DESC LIMIT 50;

// T6 — articles quoted by my follows' notes
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(f:User)
MATCH (f)<-[:AUTHOR]-(:Stored {kind: 1})-[:QUOTE]->(a:Address)<-[:ADDRESS]-(article:Stored)
RETURN article, count(*) AS quotes ORDER BY quotes DESC LIMIT 50;

// T8 — a community's approved posts and their authors
MATCH (c:Address {id: $community})<-[:COMMUNITY]-(approval:Stored {kind: 4550})-[:APPROVED]->(post:Stored)-[:AUTHOR]->(author:User)
RETURN post, author;

// T9 — who NIP-85 service S ranks >= 80
MATCH (:User {pubkey: $service})<-[:AUTHOR]-(:Stored {kind: 30382})-[a:SUBJECT]->(u:User)
WHERE a.rank >= 80
RETURN u, a.rank ORDER BY a.rank DESC;

// T10 — events my follows cite that the relay does not hold
MATCH (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(f:User)
MATCH (f)<-[:AUTHOR]-(n:Stored)-[:MENTION|QUOTE|PARENT|ROOT]->(missing:Event)
WHERE NOT missing:Stored
RETURN missing.id, count(*) AS citations ORDER BY citations DESC LIMIT 50;

// T11 — hashtags used alongside #bitcoin in the last day
MATCH (:Tag {key: 't:bitcoin'})<-[:HASHTAG]-(n:Stored)-[:HASHTAG]->(o:Tag)
WHERE n.created_at >= $since AND o.key <> 't:bitcoin'
RETURN o.value, count(*) AS uses ORDER BY uses DESC LIMIT 20;

// T12 — who reported X as a PERSON (not one of X's notes), among the people I follow
MATCH (:User {pubkey: $x})<-[r:REPORTED_USER]-(:Stored)-[:AUTHOR]->(reporter:User)
WHERE EXISTS { (:Address {id: '3:' + $me + ':'})<-[:ADDRESS]-(:Stored)-[:FOLLOW]->(reporter) }
RETURN reporter, r.report;

// T13 — users with reports that count: the standard categories only, so types clients
// invented for minor things (`swearing` is category `other`) drop out
MATCH (u:User)<-[r:REPORTED_USER|REPORTED_AUTHOR]-(rep:Stored)
WHERE r.report IN ['impersonation', 'spam', 'illegal', 'malware'] AND rep.created_at >= $since
RETURN u, type(r) AS about, count(*) AS reports ORDER BY reports DESC LIMIT 50;

// T14 — one invented type, by its text
MATCH (:Stored)-[r:REPORTED {report_raw: 'swearing'}]->(n:Stored) RETURN n;

// Hybrid — full-text search in Vespa first (a NIP-50 REQ), then the graph
UNWIND $ids AS id
MATCH (n:Event:Stored {id: id})<-[:REACTED]-(:Stored)-[:AUTHOR]->(r:User)
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
