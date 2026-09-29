/*
 * Copyright (c) 2026 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.neo4j.eventstore.engine.client

import com.vitorpamplona.neo4j.eventstore.engine.ApplyOutcome
import com.vitorpamplona.neo4j.eventstore.engine.EdgeRow
import com.vitorpamplona.neo4j.eventstore.engine.EdgeView
import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.NodeView
import com.vitorpamplona.neo4j.eventstore.engine.derive.AddressKey
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.derive.Extractors
import com.vitorpamplona.neo4j.eventstore.engine.derive.GraphDoc
import com.vitorpamplona.neo4j.eventstore.engine.derive.NodeKind
import com.vitorpamplona.neo4j.eventstore.engine.derive.wins
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.engine.schema.RelTypes
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.TransactionContext
import org.neo4j.driver.Value
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.exceptions.FatalDiscoveryException
import org.neo4j.driver.exceptions.SecurityException
import org.neo4j.driver.exceptions.TransactionTerminatedException

/**
 * [GraphIndex] over a Neo4j server, reached through the Apache-2.0 driver only (the server is
 * GPLv3 and is never linked). `ProjectionIT` holds it to [InMemoryGraphIndex]'s [dump]s.
 *
 * Every event (and every removal) is its own managed write transaction, and the driver retries
 * the callback on a transient failure (a deadlock between the two writer processes on a hub
 * node, say) — so every step here is written to be safe to re-run.
 *
 * CONCURRENCY. Two processes apply at once, so check-then-write must hold a lock: each apply
 * first WRITES to the nodes its decision depends on — the event node (duplicates, the fence) and
 * the slot's anchor, the author `:User` or the `:Address` (supersession) — which takes their
 * exclusive locks until commit. A competing transaction on the same slot then waits and sees the
 * winner.
 */
class Neo4jGraphIndex(
    private val driver: Driver,
    private val database: String = SchemaInstaller.DEFAULT_DATABASE,
    private val deriver: EdgeDeriver = EdgeDeriver(),
    private val fenceSeconds: Long = InMemoryGraphIndex.DEFAULT_FENCE_SECONDS,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) : GraphIndex {
    // One writer per process at a time: the feed consumer and the reconciler would otherwise
    // deadlock each other on the same hubs for no gain.
    private val writeLock = Mutex()

    private fun config() = SessionConfig.forDatabase(database)

    override suspend fun apply(
        events: List<Event>,
        authoritative: Boolean,
    ): ApplyOutcome {
        if (events.isEmpty()) return ApplyOutcome()
        val docs =
            events.map { event ->
                if (!deriver.policy.admits(event.kind)) null else event to deriver.derive(event)
            }
        return writeLock.withLock {
            withContext(Dispatchers.IO) {
                driver.session(config()).use { session ->
                    var outcome = ApplyOutcome(excluded = docs.count { it == null })
                    // One transaction PER EVENT: observed on 2026.09, a statement that reads a node
                    // an earlier statement in the same transaction deleted can fail with "Node …
                    // has been deleted in this transaction" (intermittently) instead of skipping
                    // it — and an apply may delete (a displaced incumbent's orphans) before the
                    // next event reads. Batching is the bulk importer's job, not the live path's.
                    for (pair in docs) {
                        if (pair == null) continue
                        val (event, doc) = pair
                        outcome +=
                            try {
                                session.executeWrite { tx -> applyOne(tx, doc, authoritative) }
                            } catch (e: ClientException) {
                                // The graph refuses THIS event (a constraint, a value it cannot
                                // index): isolate it, or one poison event would fail every batch
                                // it rides in, every reconcile of its window, forever. What is
                                // about the connection, not the event, still throws.
                                if (e is SecurityException || e is FatalDiscoveryException || e is TransactionTerminatedException) throw e
                                ApplyOutcome(failed = listOf(event))
                            }
                    }
                    outcome
                }
            }
        }
    }

    private fun applyOne(
        tx: TransactionContext,
        doc: GraphDoc,
        authoritative: Boolean,
    ): ApplyOutcome {
        // Lock the event node (creating it as a stub if new) and read its state.
        val state =
            tx
                .run(
                    """
                    MERGE (e:${Labels.EVENT} {${Labels.EVENT_KEY}: ${'$'}id})
                    SET e.__lock = true REMOVE e.__lock
                    WITH e
                    OPTIONAL MATCH (r:${Labels.REMOVED} {id: ${'$'}id})
                    RETURN e:${Labels.STORED} AS stored, r.at AS removedAt
                    """.trimIndent(),
                    mapOf("id" to doc.id),
                ).single()
        if (state["stored"].asBoolean()) return ApplyOutcome(duplicate = 1)
        if (!authoritative && !state["removedAt"].isNull && state["removedAt"].asLong() >= nowSecs() - fenceSeconds) {
            dropIfOrphan(tx, NodeKind.EVENT, doc.id)
            return ApplyOutcome(fenced = 1)
        }

        val incumbent = lockSlotAndFindIncumbent(tx, doc)
        if (incumbent != null) {
            if (!authoritative && wins(incumbent.second, incumbent.first, doc.createdAt, doc.id)) {
                dropIfOrphan(tx, NodeKind.EVENT, doc.id)
                cleanupSlotAnchor(tx, doc)
                return ApplyOutcome(stale = 1)
            }
            // Keep what the new version is about to reference (its author, its address, shared
            // targets): deleting a node and re-MERGEing its key in one transaction is the
            // read-after-delete pattern apply() avoids.
            // That includes the incumbent itself when the new version tags it (an `e` to the
            // previous version): it must stay a stub, not be deleted and re-created.
            unapplyStored(
                tx,
                incumbent.first,
                keep =
                    doc.edges.mapTo(HashSet()) { it.target.kind to it.target.key } + (NodeKind.EVENT to doc.id),
            )
        }
        write(tx, doc)
        return ApplyOutcome(applied = 1)
    }

    /** Locks the slot's anchor node and returns the incumbent's (id, created_at), if any. */
    private fun lockSlotAndFindIncumbent(
        tx: TransactionContext,
        doc: GraphDoc,
    ): Pair<String, Long>? =
        doc.slot?.let { slot ->
            val key = AddressKey.parse(slot.address)
            tx
                .run(
                    """
                    MERGE (a:${Labels.ADDRESS} {${Labels.ADDRESS_KEY}: ${'$'}address})
                    ON CREATE SET a.kind = ${'$'}kind, a.pubkey = ${'$'}pubkey, a.d = ${'$'}d
                    SET a.__lock = true REMOVE a.__lock
                    WITH a
                    OPTIONAL MATCH (a)<-[:$ADDRESS]-(old:${Labels.STORED})
                    WHERE old.${Labels.EVENT_KEY} <> ${'$'}id
                    RETURN old.${Labels.EVENT_KEY} AS id, old.created_at AS createdAt
                    """.trimIndent(),
                    mapOf(
                        "address" to slot.address,
                        "kind" to (key?.kind ?: -1).toLong(),
                        "pubkey" to (key?.pubkey ?: ""),
                        "d" to (key?.d ?: ""),
                        "id" to doc.id,
                    ),
                ).list()
                .firstOrNull { !it["id"].isNull }
                ?.let { it["id"].asString() to it["createdAt"].asLong() }
        }

    // A skipped apply may have just MERGEd its slot anchor into existence; leave no orphan.
    private fun cleanupSlotAnchor(
        tx: TransactionContext,
        doc: GraphDoc,
    ) {
        doc.slot?.let { slot -> dropAddressIfOrphan(tx, slot.address)?.let { dropIfOrphan(tx, NodeKind.USER, it) } }
    }

    /**
     * Stores [doc] in ONE statement: the node, its fence cleared, every edge group (a unit `CALL`
     * per target kind keeps the row count at one), and the author's curated names. Each
     * statement is a blocking round trip inside the event's transaction — which holds the
     * process's write lock — so folding them is most of the live path's per-event cost.
     */
    private fun write(
        tx: TransactionContext,
        doc: GraphDoc,
    ) {
        val params = HashMap<String, Any?>()
        params["id"] = doc.id
        params["props"] = InMemoryGraphIndex.eventProps(doc)
        val cypher = StringBuilder()
        cypher.append("MATCH (e:${Labels.EVENT} {${Labels.EVENT_KEY}: \$id}) SET e:${Labels.STORED}, e += \$props ")
        cypher.append("WITH e OPTIONAL MATCH (r:${Labels.REMOVED} {id: \$id}) DELETE r WITH e ")

        for ((kind, edges) in doc.edges.groupBy { it.target.kind }) {
            val rows =
                edges.map { edge ->
                    require(RelTypes.isSafe(edge.type)) { "unsafe relationship type ${edge.type}" }
                    val row = HashMap<String, Any>()
                    row["key"] = edge.target.key
                    row["type"] = edge.type
                    row["props"] = edge.props
                    when (kind) {
                        NodeKind.TAG -> {
                            row["name"] = edge.target.key.substringBefore(':')
                            row["value"] = edge.target.key.substringAfter(':')
                        }

                        NodeKind.ADDRESS -> {
                            val key = AddressKey.parse(edge.target.key)
                            row["kind"] = (key?.kind ?: -1).toLong()
                            row["pubkey"] = key?.pubkey ?: ""
                            row["d"] = key?.d ?: ""
                        }

                        else -> {
                            Unit
                        }
                    }
                    row
                }
            val param = "rows_${kind.name.lowercase()}"
            params[param] = rows
            val merge =
                when (kind) {
                    NodeKind.EVENT -> {
                        "MERGE (t:${Labels.EVENT} {${Labels.EVENT_KEY}: row.key})"
                    }

                    NodeKind.USER -> {
                        "MERGE (t:${Labels.USER} {${Labels.USER_KEY}: row.key})"
                    }

                    NodeKind.TAG -> {
                        "MERGE (t:${Labels.TAG} {${Labels.TAG_KEY}: row.key}) " +
                            "ON CREATE SET t.name = row.name, t.value = row.value"
                    }

                    NodeKind.ADDRESS -> {
                        "MERGE (t:${Labels.ADDRESS} {${Labels.ADDRESS_KEY}: row.key}) " +
                            "ON CREATE SET t.kind = row.kind, t.pubkey = row.pubkey, t.d = row.d"
                    }
                }
            cypher.append("CALL (e) { UNWIND \$$param AS row $merge ")
            cypher.append("CREATE (e)-[r:\$(row.type)]->(t) SET r = row.props ")
            if (kind == NodeKind.ADDRESS) {
                cypher.append(
                    "WITH t, row WHERE row.pubkey <> '' " +
                        "MERGE (o:${Labels.USER} {${Labels.USER_KEY}: row.pubkey}) MERGE (t)-[:$AUTHOR]->(o) ",
                )
            }
            cypher.append("} ")
        }

        // After the USER group, which MERGEd the author (every doc has its `by_` edge).
        doc.authorProps?.let { values ->
            params["pk"] = doc.pubkey
            params["authorProps"] = values
            cypher.append(
                "CALL (e) { MATCH (u:${Labels.USER} {${Labels.USER_KEY}: \$pk}) " +
                    Extractors.USER_FIELDS.joinToString(" ") { "SET u.$it = null" } + " SET u += \$authorProps } ",
            )
        }
        tx.run(cypher.toString(), params).consume()
    }

    override suspend fun unapply(ids: List<String>) {
        if (ids.isEmpty()) return
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                driver.session(config()).use { session ->
                    val now = nowSecs()
                    // One transaction per id, for the reason apply() gives.
                    for (id in ids) {
                        session.executeWrite { tx ->
                            // Lock the event's key FIRST, held or not: apply() takes the same lock
                            // before it reads the fence, so a concurrent apply of an id this
                            // removal fences either commits first (and is stripped here) or
                            // waits and then sees the fence. Without it, both would read the
                            // other's pre-commit state and the event would end held AND fenced.
                            tx
                                .run(
                                    "MERGE (e:${Labels.EVENT} {${Labels.EVENT_KEY}: \$id}) SET e.__lock = true REMOVE e.__lock",
                                    mapOf("id" to id),
                                ).consume()
                            val wasStored = unapplyStored(tx, id)
                            tx.run("MERGE (r:${Labels.REMOVED} {id: \$id}) SET r.at = \$now", mapOf("id" to id, "now" to now)).consume()
                            // The lock may have minted the node; unapplyStored already settled a held one.
                            if (!wasStored) dropOrphans(tx, NodeKind.EVENT, listOf(id))
                        }
                    }
                }
            }
        }
    }

    /**
     * Strips a held event to a stub (or deletes it) and drops every node left unreferenced.
     * Returns false, touching nothing, when [id] is not held.
     */
    private fun unapplyStored(
        tx: TransactionContext,
        id: String,
        keep: Set<Pair<NodeKind, String>> = emptySet(),
    ): Boolean {
        // Lock, and learn what the event owned: its kind (kind 0 owns its author's names).
        val kind =
            tx
                .run(
                    "MATCH (e:${Labels.EVENT}:${Labels.STORED} {${Labels.EVENT_KEY}: \$id}) " +
                        "SET e.__lock = true REMOVE e.__lock RETURN e.kind AS kind",
                    mapOf("id" to id),
                ).list()
                .firstOrNull()
                ?.get("kind")
                ?.asLong() ?: return false
        if (kind == 0L) {
            // The author's names come from their CURRENT kind 0. Online there is only ever one
            // held; after a bulk load an older one can sit beside it until the reconciler removes
            // it — so fall back to whichever kind 0 is still held (its node keeps the names too).
            tx
                .run(
                    "MATCH (e:${Labels.EVENT} {${Labels.EVENT_KEY}: \$id})-[:$AUTHOR]->(u:${Labels.USER}) " +
                        Extractors.USER_FIELDS.joinToString(" ") { "SET u.$it = null" } +
                        // Through the `0:<pubkey>:` address every kind 0 has: one hop to the
                        // author's kind 0s, not a walk over everything they ever signed.
                        " WITH e, u OPTIONAL MATCH (e)-[:$ADDRESS]->(:${Labels.ADDRESS})<-[:$ADDRESS]-(k:${Labels.STORED}) " +
                        "WHERE k.${Labels.EVENT_KEY} <> \$id " +
                        "WITH u, k ORDER BY k.created_at DESC, k.${Labels.EVENT_KEY} ASC LIMIT 1 " +
                        "WITH u, k WHERE k IS NOT NULL " +
                        Extractors.USER_FIELDS.joinToString(" ") { "SET u.$it = k.$it" },
                    mapOf("id" to id),
                ).consume()
        }
        val targets =
            tx
                .run(
                    """
                    MATCH (e:${Labels.EVENT}:${Labels.STORED} {${Labels.EVENT_KEY}: ${'$'}id})
                    OPTIONAL MATCH (e)-[r]->(t)
                    WITH e, collect(r) AS rels, collect(DISTINCT t) AS targets
                    FOREACH (x IN rels | DELETE x)
                    REMOVE e:${Labels.STORED}
                    SET e = {${Labels.EVENT_KEY}: ${'$'}id}
                    WITH e, targets
                    CALL (e) { WITH e WHERE NOT ${'$'}keepSelf AND NOT EXISTS { (e)<--() } DELETE e }
                    UNWIND targets AS t
                    RETURN labels(t) AS labels,
                           coalesce(t.${Labels.EVENT_KEY}, t.${Labels.USER_KEY}, t.${Labels.TAG_KEY}) AS key
                    """.trimIndent(),
                    mapOf("id" to id, "keepSelf" to ((NodeKind.EVENT to id) in keep)),
                ).list()
                .map { primaryKind(it["labels"].asList { v -> v.asString() }) to it["key"].asString() }
                .filter { it !in keep }

        // Non-users first (an address takes its AUTHOR edge with it), then users, so each check
        // runs after every edge that could have kept it alive is gone.
        val byKind = targets.groupBy({ it.first }, { it.second })
        byKind[NodeKind.EVENT]?.let { dropOrphans(tx, NodeKind.EVENT, it) }
        byKind[NodeKind.TAG]?.let { dropOrphans(tx, NodeKind.TAG, it) }
        val users = HashSet<String>(byKind[NodeKind.USER] ?: emptyList())
        byKind[NodeKind.ADDRESS]?.let { users += dropAddressOrphans(tx, it) }
        users.removeIf { (NodeKind.USER to it) in keep }
        if (users.isNotEmpty()) dropOrphans(tx, NodeKind.USER, users)
        return true
    }

    private fun dropIfOrphan(
        tx: TransactionContext,
        kind: NodeKind,
        key: String,
    ) = dropOrphans(tx, kind, listOf(key))

    /**
     * Deletes each of [keys] that nothing references any more, in one statement.
     *
     * LOCK, THEN TEST: the test must see the committed state AFTER this transaction holds the
     * node's lock. Tested first, a concurrent writer (the other process) could add an edge
     * between the test and the delete — and the delete would then fail at commit, or, for a
     * `DETACH`, silently take that writer's committed edge with it. Keys are sorted so two
     * writers take the locks in one order.
     */
    private fun dropOrphans(
        tx: TransactionContext,
        kind: NodeKind,
        keys: Collection<String>,
    ) {
        if (keys.isEmpty()) return
        val (label, prop) = labelAndKey(kind)
        val condition =
            when (kind) {
                // A HELD event is never dropped for lack of references; a user also has no outgoing edges.
                NodeKind.EVENT -> "NOT t:${Labels.STORED} AND NOT EXISTS { (t)<--() }"

                NodeKind.USER -> "NOT EXISTS { (t)--() }"

                else -> "NOT EXISTS { (t)<--() }"
            }
        tx
            .run(
                "UNWIND \$keys AS key MATCH (t:$label {$prop: key}) SET t.__lock = true REMOVE t.__lock " +
                    "WITH t WHERE $condition DELETE t",
                mapOf("keys" to keys.sorted()),
            ).consume()
    }

    /** Address [dropOrphans]: takes each dropped address's AUTHOR edge with it and returns the owners to re-check. */
    private fun dropAddressOrphans(
        tx: TransactionContext,
        addresses: Collection<String>,
    ): List<String> =
        tx
            .run(
                "UNWIND \$keys AS key MATCH (t:${Labels.ADDRESS} {${Labels.ADDRESS_KEY}: key}) " +
                    "SET t.__lock = true REMOVE t.__lock WITH t WHERE NOT EXISTS { (t)<--() } " +
                    "WITH t, [(t)-[:$AUTHOR]->(o:${Labels.USER}) | o.${Labels.USER_KEY}] AS owners " +
                    "DETACH DELETE t UNWIND owners AS owner RETURN DISTINCT owner",
                mapOf("keys" to addresses.sorted()),
            ).list { it["owner"].asString() }

    private fun dropAddressIfOrphan(
        tx: TransactionContext,
        address: String,
    ): String? = dropAddressOrphans(tx, listOf(address)).firstOrNull()

    override suspend fun visitIds(
        since: Long,
        until: Long,
        pageSize: Int,
        onPage: suspend (List<IdAndTime>) -> Boolean,
    ) {
        // No sentinel cursor: `since - 1` overflows for since = Long.MIN_VALUE (the sweep's head).
        var first = true
        var lastCreatedAt = since
        var lastId = ""
        while (true) {
            val page =
                withContext(Dispatchers.IO) {
                    driver.session(config()).use { session ->
                        session.executeRead { tx ->
                            tx
                                .run(
                                    """
                                    MATCH (e:${Labels.STORED})
                                    WHERE e.created_at >= ${'$'}from AND e.created_at <= ${'$'}until
                                      AND (${'$'}first OR e.created_at > ${'$'}ca OR (e.created_at = ${'$'}ca AND e.${Labels.EVENT_KEY} > ${'$'}id))
                                    RETURN e.created_at AS ca, e.${Labels.EVENT_KEY} AS id
                                    ORDER BY ca, id LIMIT ${'$'}n
                                    """.trimIndent(),
                                    mapOf(
                                        // The seek starts at the cursor, not at `since`: the OR below is
                                        // only a filter, so without this every page re-read the window.
                                        "from" to maxOf(since, lastCreatedAt),
                                        "until" to until,
                                        "first" to first,
                                        "ca" to lastCreatedAt,
                                        "id" to lastId,
                                        "n" to pageSize.toLong(),
                                    ),
                                ).list { IdAndTime(it["ca"].asLong(), it["id"].asString()) }
                        }
                    }
                }
            if (page.isEmpty()) return
            if (!onPage(page)) return
            if (page.size < pageSize) return
            first = false
            lastCreatedAt = page.last().createdAt
            lastId = page.last().id
        }
    }

    override suspend fun edgesOf(id: String): List<EdgeView>? =
        withContext(Dispatchers.IO) {
            driver.session(config()).use { session ->
                session.executeRead { tx ->
                    val rows =
                        tx
                            .run(
                                "MATCH (e:${Labels.EVENT}:${Labels.STORED} {${Labels.EVENT_KEY}: \$id}) " +
                                    "OPTIONAL MATCH (e)-[r]->(t) " +
                                    "RETURN type(r) AS type, labels(t) AS labels, " +
                                    "coalesce(t.${Labels.EVENT_KEY}, t.${Labels.USER_KEY}, t.${Labels.TAG_KEY}) AS key, " +
                                    "properties(r) AS props",
                                mapOf("id" to id),
                            ).list()
                    if (rows.isEmpty()) {
                        null
                    } else {
                        rows
                            .filter { !it["type"].isNull }
                            .map {
                                EdgeView(
                                    type = it["type"].asString(),
                                    targetLabel = primaryKind(it["labels"].asList { v -> v.asString() }).label,
                                    targetKey = it["key"].asString(),
                                    props = normalize(it["props"]),
                                )
                            }.sortedWith(InMemoryGraphIndex.EDGE_ORDER)
                    }
                }
            }
        }

    override suspend fun sweepFence(olderThanSecs: Long) {
        withContext(Dispatchers.IO) {
            driver.session(config()).use { session ->
                do {
                    val deleted =
                        session.executeWrite { tx ->
                            tx
                                .run(
                                    "MATCH (r:${Labels.REMOVED}) WHERE r.at < \$t WITH r LIMIT 10000 DELETE r RETURN count(*) AS n",
                                    mapOf("t" to olderThanSecs),
                                ).single()["n"]
                                .asLong()
                        }
                } while (deleted > 0)
            }
        }
    }

    override suspend fun dump(): GraphDump =
        withContext(Dispatchers.IO) {
            driver.session(config()).use { session ->
                session.executeRead { tx ->
                    val nodes =
                        tx
                            .run(
                                "MATCH (n) WHERE NOT n:${Labels.REMOVED} AND NOT n:${Labels.META} " +
                                    "RETURN labels(n) AS labels, properties(n) AS props",
                            ).list { rec ->
                                val labels = rec["labels"].asList { it.asString() }
                                val kind = primaryKind(labels)
                                val (_, keyProp) = labelAndKey(kind)
                                val props = normalize(rec["props"]).toMutableMap()
                                val key = props.remove(keyProp) as String
                                NodeView(kind.label, key, Labels.STORED in labels, props)
                            }.toSet()
                    val edges =
                        tx
                            .run(
                                "MATCH (a)-[r]->(b) RETURN labels(a) AS la, " +
                                    "coalesce(a.${Labels.EVENT_KEY}, a.${Labels.USER_KEY}, a.${Labels.TAG_KEY}) AS ka, type(r) AS type, " +
                                    "labels(b) AS lb, coalesce(b.${Labels.EVENT_KEY}, b.${Labels.USER_KEY}, b.${Labels.TAG_KEY}) AS kb, " +
                                    "properties(r) AS props",
                            ).list { rec ->
                                EdgeRow(
                                    primaryKind(rec["la"].asList { it.asString() }).label,
                                    rec["ka"].asString(),
                                    rec["type"].asString(),
                                    primaryKind(rec["lb"].asList { it.asString() }).label,
                                    rec["kb"].asString(),
                                    normalize(rec["props"]),
                                )
                            }.toSet()
                    GraphDump(nodes, edges)
                }
            }
        }

    override fun close() = Unit

    companion object {
        private val AUTHOR = safe(Relation.AUTHOR.name)
        private val ADDRESS = safe(Relation.ADDRESS.name)

        private fun safe(type: String): String {
            require(RelTypes.isSafe(type)) { "unsafe relationship type $type" }
            return type
        }

        fun primaryKind(labels: List<String>): NodeKind =
            when {
                Labels.EVENT in labels -> NodeKind.EVENT
                Labels.USER in labels -> NodeKind.USER
                Labels.ADDRESS in labels -> NodeKind.ADDRESS
                Labels.TAG in labels -> NodeKind.TAG
                else -> error("not a projection node: $labels")
            }

        fun labelAndKey(kind: NodeKind): Pair<String, String> =
            when (kind) {
                NodeKind.EVENT -> Labels.EVENT to Labels.EVENT_KEY
                NodeKind.USER -> Labels.USER to Labels.USER_KEY
                NodeKind.ADDRESS -> Labels.ADDRESS to Labels.ADDRESS_KEY
                NodeKind.TAG -> Labels.TAG to Labels.TAG_KEY
            }

        /** Neo4j's property shapes → the port's: Long, String, List<String> (and Boolean/Double pass through). */
        fun normalize(value: Value): Map<String, Any> {
            if (value.isNull) return emptyMap()
            val out = HashMap<String, Any>()
            for ((k, v) in value.asMap()) {
                out[k] =
                    when (v) {
                        is List<*> -> v.map { it.toString() }
                        is Int -> v.toLong()
                        else -> v ?: continue
                    }
            }
            return out
        }
    }
}
