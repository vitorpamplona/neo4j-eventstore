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
package com.vitorpamplona.neo4j.eventstore.engine.memory

import com.vitorpamplona.neo4j.eventstore.engine.ApplyOutcome
import com.vitorpamplona.neo4j.eventstore.engine.EdgeRow
import com.vitorpamplona.neo4j.eventstore.engine.EdgeView
import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.HeldRef
import com.vitorpamplona.neo4j.eventstore.engine.NodeView
import com.vitorpamplona.neo4j.eventstore.engine.derive.AddressKey
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.derive.Extractors
import com.vitorpamplona.neo4j.eventstore.engine.derive.GraphDoc
import com.vitorpamplona.neo4j.eventstore.engine.derive.NodeKind
import com.vitorpamplona.neo4j.eventstore.engine.derive.NodeRef
import com.vitorpamplona.neo4j.eventstore.engine.derive.wins
import com.vitorpamplona.neo4j.eventstore.engine.schema.Derivation
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The EXECUTABLE SPECIFICATION of [GraphIndex]: the semantics in plain collections, no Neo4j.
 * Unit tests prove the projection's properties here (order-independence, convergence); the
 * integration suite then holds the Neo4j binding to the same [dump]s.
 *
 * The graph kept here is exactly the one the spec describes: held events plus the nodes some
 * held event points at. A node nothing points at any more is deleted on the spot (a stub, a tag,
 * an address, a user), so the graph after any history equals the graph a fresh projection of the
 * source's current state would build — the property convergence is tested against.
 */
class InMemoryGraphIndex(
    private val deriver: EdgeDeriver = EdgeDeriver(),
    private val fenceSeconds: Long = DEFAULT_FENCE_SECONDS,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) : GraphIndex {
    private val lock = Mutex()

    private val held = HashMap<String, GraphDoc>() // stored events, by id
    private val stubs = HashSet<String>() // referenced-but-not-held event ids
    private val users = HashMap<String, MutableMap<String, Any>>()
    private val addresses = HashMap<String, AddressKey>()
    private val tagNodes = HashMap<String, Pair<String, String>>()
    private val incoming = HashMap<NodeRef, Int>()
    private val slots = HashMap<String, String>() // slot address -> the held event in it
    private val removedAt = HashMap<String, Long>()
    private val stamps = HashMap<String, Long>() // held id -> the derivation stamp it was written with

    // What this build writes on every held node (Derivation): a node with any other value was
    // written by another derivation and is the reconciler's to re-derive.
    private val stamp = Derivation.stamp(deriver.policy)

    override suspend fun apply(
        events: List<Event>,
        authoritativeAsOf: Long?,
    ): ApplyOutcome =
        lock.withLock {
            var outcome = ApplyOutcome()
            for (event in events) outcome += applyOne(event, authoritativeAsOf)
            outcome
        }

    private fun applyOne(
        event: Event,
        authoritativeAsOf: Long?,
    ): ApplyOutcome {
        if (!deriver.policy.admits(event.kind)) return ApplyOutcome(excluded = 1)
        if (event.id in held) return ApplyOutcome(duplicate = 1)
        val at = removedAt[event.id]
        if (at != null && fenceHolds(at, authoritativeAsOf, nowSecs(), fenceSeconds)) return ApplyOutcome(fenced = 1)
        val doc = deriver.derive(event)
        val incumbent = doc.slot?.let { slots[it.address] }?.let { held[it] }
        if (incumbent != null) {
            if (wins(incumbent.createdAt, incumbent.id, doc.createdAt, doc.id)) {
                // Kept even under authority: the source may hold it too (it superseded this event
                // after the reconciler read it). The caller checks, and removes it if not.
                val outranked = if (authoritativeAsOf != null) mapOf(doc.id to incumbent.id) else emptyMap()
                return ApplyOutcome(stale = 1, outranked = outranked)
            }
            unapplyHeld(incumbent)
        }
        write(doc)
        removedAt.remove(doc.id)
        return ApplyOutcome(applied = 1)
    }

    override suspend fun rederive(events: List<Event>): ApplyOutcome =
        lock.withLock {
            var outcome = ApplyOutcome()
            for (event in events) {
                outcome +=
                    when {
                        !deriver.policy.admits(event.kind) -> {
                            ApplyOutcome(excluded = 1)
                        }

                        else -> {
                            val old = held[event.id]
                            if (old == null) {
                                ApplyOutcome(stale = 1)
                            } else {
                                // Unapply-then-write leaves exactly what a fresh apply of the new
                                // derivation would: shared targets survive through their counts.
                                unapplyHeld(old)
                                write(deriver.derive(event))
                                ApplyOutcome(applied = 1)
                            }
                        }
                    }
            }
            outcome
        }

    private fun write(doc: GraphDoc) {
        held[doc.id] = doc
        stamps[doc.id] = stamp
        stubs.remove(doc.id)
        doc.slot?.let { slots[it.address] = doc.id }
        for (edge in doc.edges) {
            ensureNode(edge.target)
            incoming.merge(edge.target, 1, Int::plus)
        }
        val author = doc.authorKey
        if (doc.authorProps != null && author != null) {
            // The AUTHOR edge above created the node; never key on the raw pubkey (GraphDoc.authorKey).
            val props = users.getOrPut(author) { HashMap() }
            Extractors.USER_FIELDS.forEach { props.remove(it) }
            props.putAll(doc.authorProps)
        }
    }

    private fun ensureNode(ref: NodeRef) {
        when (ref.kind) {
            NodeKind.EVENT -> {
                if (ref.key !in held) stubs.add(ref.key)
            }

            NodeKind.USER -> {
                users.getOrPut(ref.key) { HashMap() }
            }

            NodeKind.TAG -> {
                tagNodes.getOrPut(ref.key) { ref.key.substringBefore(':') to ref.key.substringAfter(':') }
            }

            NodeKind.ADDRESS -> {
                if (ref.key !in addresses) {
                    val key = AddressKey.parse(ref.key) ?: AddressKey(ref.key, -1, "", "")
                    addresses[ref.key] = key
                    // Every address points at its owner, stub or not: the one AUTHOR no event states.
                    if (key.pubkey.isNotEmpty()) {
                        val owner = NodeRef(NodeKind.USER, key.pubkey)
                        ensureNode(owner)
                        incoming.merge(owner, 1, Int::plus)
                    }
                }
            }
        }
    }

    override suspend fun unapply(ids: List<String>) {
        lock.withLock {
            val now = nowSecs()
            for (id in ids) {
                held[id]?.let { unapplyHeld(it) }
                removedAt[id] = now
            }
        }
    }

    private fun unapplyHeld(doc: GraphDoc) {
        held.remove(doc.id)
        stamps.remove(doc.id)
        doc.slot?.let { slots.remove(it.address, doc.id) }
        if (doc.authorProps != null) {
            doc.authorKey?.let { users[it] }?.let { props -> Extractors.USER_FIELDS.forEach { props.remove(it) } }
        }
        // The node survives as a stub while anything still points at it.
        val self = NodeRef(NodeKind.EVENT, doc.id)
        if ((incoming[self] ?: 0) > 0) stubs.add(doc.id)
        for (edge in doc.edges) {
            decrement(edge.target)
            dropIfOrphan(edge.target)
        }
    }

    private fun decrement(ref: NodeRef) {
        val n = (incoming[ref] ?: 0) - 1
        if (n <= 0) incoming.remove(ref) else incoming[ref] = n
    }

    private fun dropIfOrphan(ref: NodeRef) {
        if ((incoming[ref] ?: 0) > 0) return
        when (ref.kind) {
            NodeKind.EVENT -> {
                stubs.remove(ref.key)
            }

            // a HELD event is never dropped for lack of references
            NodeKind.USER -> {
                users.remove(ref.key)
            }

            NodeKind.TAG -> {
                tagNodes.remove(ref.key)
            }

            NodeKind.ADDRESS -> {
                val key = addresses.remove(ref.key) ?: return
                if (key.pubkey.isNotEmpty()) {
                    val owner = NodeRef(NodeKind.USER, key.pubkey)
                    decrement(owner)
                    dropIfOrphan(owner)
                }
            }
        }
    }

    override suspend fun visitIds(
        since: Long,
        until: Long,
        pageSize: Int,
        onPage: suspend (List<HeldRef>) -> Boolean,
    ) {
        val all =
            lock.withLock {
                held.values
                    .filter { it.createdAt in since..until }
                    .map { HeldRef(it.createdAt, it.id, stamps[it.id] ?: 0L) }
                    .sortedWith(compareBy<HeldRef> { it.createdAt }.thenBy { it.id })
            }
        for (page in all.chunked(pageSize)) if (!onPage(page)) return
    }

    override suspend fun edgesOf(id: String): List<EdgeView>? =
        lock.withLock {
            held[id]?.edges?.map { EdgeView(it.type, it.target.kind.label, it.target.key, it.props) }?.sortedWith(EDGE_ORDER)
        }

    override suspend fun sweepFence(olderThanSecs: Long) {
        lock.withLock { removedAt.entries.removeIf { it.value < olderThanSecs } }
    }

    override suspend fun dump(): GraphDump =
        lock.withLock {
            val nodes = HashSet<NodeView>()
            for (doc in held.values) nodes += NodeView(Labels.EVENT, doc.id, true, eventProps(doc, stamps[doc.id] ?: 0L))
            for (id in stubs) nodes += NodeView(Labels.EVENT, id, false, emptyMap())
            for ((pk, props) in users) nodes += NodeView(Labels.USER, pk, false, HashMap(props))
            for ((id, key) in addresses) nodes += NodeView(Labels.ADDRESS, id, false, addressProps(key))
            for ((key, nv) in tagNodes) nodes += NodeView(Labels.TAG, key, false, mapOf("name" to nv.first, "value" to nv.second))

            val edges = HashSet<EdgeRow>()
            for (doc in held.values) {
                for (e in doc.edges) edges += EdgeRow(Labels.EVENT, doc.id, e.type, e.target.kind.label, e.target.key, e.props)
            }
            for ((id, key) in addresses) {
                if (key.pubkey.isNotEmpty()) edges += EdgeRow(Labels.ADDRESS, id, Relation.AUTHOR.name, Labels.USER, key.pubkey, emptyMap())
            }
            GraphDump(nodes, edges)
        }

    /**
     * This graph as another build opens it: the same nodes and edges, stamps and fence, now
     * applied through [deriver] — what a deploy does to a Neo4j database. Tests use it to hold a
     * graph an OLDER derivation wrote, which the reconciler must then re-derive.
     */
    suspend fun reopen(deriver: EdgeDeriver = EdgeDeriver()): InMemoryGraphIndex =
        lock.withLock {
            InMemoryGraphIndex(deriver, fenceSeconds, nowSecs).also { copy ->
                copy.held.putAll(held)
                copy.stubs.addAll(stubs)
                users.forEach { (k, v) -> copy.users[k] = HashMap(v) }
                copy.addresses.putAll(addresses)
                copy.tagNodes.putAll(tagNodes)
                copy.incoming.putAll(incoming)
                copy.slots.putAll(slots)
                copy.removedAt.putAll(removedAt)
                copy.stamps.putAll(stamps)
            }
        }

    override fun close() = Unit

    companion object {
        const val DEFAULT_FENCE_SECONDS = 3_600L

        // Props last: one target reached two ways is two edges that differ only there (a user
        // tagged in `p` and linked in the content is two MENTIONs, `via` p and `via` content).
        val EDGE_ORDER = compareBy<EdgeView>({ it.type }, { it.targetLabel }, { it.targetKey }, { it.props.toSortedMap().toString() })

        /** An event node's normalized properties, with the [stamp] it is written with (shared with the Neo4j binding). */
        fun eventProps(
            doc: GraphDoc,
            stamp: Long,
        ): Map<String, Any> =
            HashMap<String, Any>(doc.nodeProps).apply {
                put("kind", doc.kind.toLong())
                put("created_at", doc.createdAt)
                put(Derivation.PROPERTY, stamp)
            }

        /**
         * Whether a fence stamped at [removedAt] keeps an apply out. A live apply ([asOf] null)
         * is fenced within the window: it may be a late put racing its own removal. An
         * authoritative one only by a removal at or after the source read it was made from
         * ([asOf]): that removal is newer than the read, and applying would resurrect what the
         * source has since dropped. Same second counts as after — a resurrection is the worse
         * error, and a skipped apply is found missing again on the next pass.
         */
        fun fenceHolds(
            removedAt: Long,
            asOf: Long?,
            now: Long,
            fenceSeconds: Long,
        ): Boolean = if (asOf == null) removedAt >= now - fenceSeconds else removedAt >= asOf

        fun addressProps(key: AddressKey): Map<String, Any> = mapOf("kind" to key.kind.toLong(), "pubkey" to key.pubkey, "d" to key.d)
    }
}
