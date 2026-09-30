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
 * empty). The reconciler repairs them with AUTHORITATIVE applies (the source said the event was
 * held when it read it), and removes a held incumbent that out-ranks an authoritative apply only
 * once the source confirms it no longer holds it ([ApplyOutcome.outranked]).
 */
interface GraphIndex : AutoCloseable {
    /**
     * Projects [events]; see the contract.
     *
     * [authoritativeAsOf] is the reconciler's: the source held these events when it was read at
     * that second. Such an apply bypasses the recent-removal fence — unless the fence was stamped
     * AT OR AFTER the read: that removal is newer than what the source said, and re-applying
     * would resurrect an event the source has since dropped. It still respects NIP-01 in its slot:
     * an incumbent that out-ranks it is kept (the source may hold it too, having superseded this
     * one since the read) and reported in [ApplyOutcome.outranked] for the caller to check.
     */
    suspend fun apply(
        events: List<Event>,
        authoritativeAsOf: Long? = null,
    ): ApplyOutcome

    /** Unprojects [ids] (the stub rule of spec §4.1) and fences each id against a late re-put. */
    suspend fun unapply(ids: List<String>)

    /**
     * Rewrites the projection of each HELD event among [events] with the running derivation —
     * what the reconciler does to an event whose [HeldRef.derived] stamp is stale (a mapper fix, a
     * Quartz bump). In place and in one transaction per event: its old edges are unapplied,
     * keeping every node the new derivation references (no delete-and-re-create), and the new
     * ones written. No fence and no slot contest: the event is held and stays held. An event not
     * held (removed meanwhile) is left alone and counted [ApplyOutcome.stale].
     */
    suspend fun rederive(events: List<Event>): ApplyOutcome

    /**
     * Every held (`:Stored`) event with `created_at` in `[since, until]`, ascending by
     * (created_at, id), in pages, each with the derivation stamp it was written with — the
     * reconciler's side of the diff. [onPage] returns whether to continue.
     */
    suspend fun visitIds(
        since: Long,
        until: Long,
        pageSize: Int = 10_000,
        onPage: suspend (List<HeldRef>) -> Boolean,
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

/**
 * A held event as [GraphIndex.visitIds] lists it: its (created_at, id) and the derivation stamp it
 * was written with ([com.vitorpamplona.neo4j.eventstore.engine.schema.Derivation.stamp]; 0 for a
 * node written before stamps existed).
 */
data class HeldRef(
    val createdAt: Long,
    val id: String,
    val derived: Long,
)

/** Per-call tallies of what [GraphIndex.apply] did with each event. */
data class ApplyOutcome(
    val applied: Int = 0,
    val duplicate: Int = 0,
    val stale: Int = 0,
    val fenced: Int = 0,
    val excluded: Int = 0,
    /**
     * Events the graph REFUSED (a non-transient error on that one event), isolated so the rest
     * of the call still applies. The caller marks them for the reconciler, which retries; a
     * transient failure (the server down, retries exhausted) throws instead.
     */
    val failed: List<Event> = emptyList(),
    /**
     * Authoritative applies only: event id → the held incumbent that out-ranked it under NIP-01
     * and was kept. The caller asks the source about each incumbent: if it is no longer held, it
     * unapplies it and applies the event again.
     */
    val outranked: Map<String, String> = emptyMap(),
) {
    operator fun plus(o: ApplyOutcome) =
        ApplyOutcome(
            applied + o.applied,
            duplicate + o.duplicate,
            stale + o.stale,
            fenced + o.fenced,
            excluded + o.excluded,
            if (o.failed.isEmpty()) failed else failed + o.failed,
            if (o.outranked.isEmpty()) outranked else outranked + o.outranked,
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
