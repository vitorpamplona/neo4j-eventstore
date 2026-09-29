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
package com.vitorpamplona.neo4j.eventstore.engine

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime

/**
 * The graph port: what the projection does to a graph, independent of where the graph lives.
 * Two implementations — [com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex], the
 * EXECUTABLE SPECIFICATION of these semantics, and the Neo4j binding — and `ProjectionIT` holds
 * them to identical [dump]s over the same deliveries.
 *
 * CONTRACT (spec §6.2): [apply] and [unapply] are idempotent and ORDER-INDEPENDENT in their
 * final state, because two writer processes deliver through two queues in any interleaving:
 * - a duplicate apply is a no-op;
 * - a replaceable / addressable event competes for its slot under the NIP-01 tiebreak, so a stale
 *   delivery is dropped and a newer one unapplies the incumbent (the source may supersede
 *   atomically and never report the loser's removal);
 * - an id unapplied within the fence window is not re-applied by a late put.
 *
 * Two residuals no local rule can see, both left to the reconciler: a remove arriving more than
 * a fence window before its own put; and, when the source supersedes WITHOUT reporting the
 * loser, a stale version delivered after its successor was itself removed (the slot looks
 * empty). The reconciler repairs them with AUTHORITATIVE applies: the source says the event is
 * held, so it bypasses the fence and displaces whatever occupies its slot.
 */
interface GraphIndex : AutoCloseable {
    /**
     * Projects [events]; see the contract. [authoritative] (the reconciler: the source holds these
     * NOW) bypasses the recent-removal fence and displaces a slot's incumbent whatever its age.
     */
    suspend fun apply(
        events: List<Event>,
        authoritative: Boolean = false,
    ): ApplyOutcome

    /** Unprojects [ids] (the stub rule of spec §4.1) and fences each id against a late re-put. */
    suspend fun unapply(ids: List<String>)

    /**
     * Every held (`:Stored`) event with `created_at` in `[since, until]`, ascending by
     * (created_at, id), in pages — the reconciler's side of the diff. [onPage] returns whether to
     * continue.
     */
    suspend fun visitIds(
        since: Long,
        until: Long,
        pageSize: Int = 10_000,
        onPage: suspend (List<IdAndTime>) -> Boolean,
    )

    /** What held event [id] contributed, sorted; null when it is not held. Debugging and parity. */
    suspend fun edgesOf(id: String): List<EdgeView>?

    /** Drops fence entries older than [olderThanSecs] (a periodic sweep keeps the fence small). */
    suspend fun sweepFence(olderThanSecs: Long)

    /**
     * The WHOLE graph, normalized for comparison — tests and debugging only (unbounded: never on
     * a production graph).
     */
    suspend fun dump(): GraphDump
}

/** Per-call tallies of what [GraphIndex.apply] did with each event. */
data class ApplyOutcome(
    val applied: Int = 0,
    val duplicate: Int = 0,
    val stale: Int = 0,
    val fenced: Int = 0,
    val excluded: Int = 0,
) {
    operator fun plus(o: ApplyOutcome) =
        ApplyOutcome(
            applied + o.applied,
            duplicate + o.duplicate,
            stale + o.stale,
            fenced + o.fenced,
            excluded + o.excluded,
        )
}

/** One outgoing relationship of a held event, as stored. [props] values: String, Long, List<String>. */
data class EdgeView(
    val type: String,
    val targetLabel: String,
    val targetKey: String,
    val props: Map<String, Any>,
)

/** A node, normalized: its primary label, key, whether it is `:Stored` (events), and properties. */
data class NodeView(
    val label: String,
    val key: String,
    val stored: Boolean,
    val props: Map<String, Any>,
)

/** A relationship, normalized by its endpoints' (label, key). */
data class EdgeRow(
    val fromLabel: String,
    val fromKey: String,
    val type: String,
    val toLabel: String,
    val toKey: String,
    val props: Map<String, Any>,
)

/** The whole graph as two sets — equal dumps mean equal graphs. */
data class GraphDump(
    val nodes: Set<NodeView>,
    val edges: Set<EdgeRow>,
)
