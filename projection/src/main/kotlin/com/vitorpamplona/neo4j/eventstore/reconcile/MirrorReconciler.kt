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
package com.vitorpamplona.neo4j.eventstore.reconcile

import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime

/**
 * Proves and repairs `graph == source` (spec §7.2), one `created_at` window at a time.
 *
 * Both sides are listed for the window and diffed as sets: an id the source holds and the graph
 * does not is MISSING (fetched and applied AUTHORITATIVELY — the source says it is held now,
 * so it bypasses the fence and displaces a stale slot incumbent that may sit in another window);
 * an id the graph holds and the source does not is EXTRA (unapplied). Windows split in
 * half while either side holds more than [maxWindowIds] in them, so memory stays bounded however
 * the corpus is distributed (a 500M-id snapshot cannot be materialized).
 *
 * Races with the live feed are benign: a missing event still queued is applied twice (the
 * second is a no-op); an extra removed ahead of its own delivery is fenced.
 */
class MirrorReconciler(
    private val source: SourceOfTruth,
    private val graph: GraphIndex,
    private val policy: GraphPolicy = GraphPolicy.Default,
    private val maxWindowIds: Int = 250_000,
    private val fetchChunk: Int = 500,
) {
    /** What one reconcile call found and fixed. */
    data class Report(
        val windows: Int = 0,
        val sourceIds: Long = 0,
        val missing: Long = 0,
        val extra: Long = 0,
        val vanished: Long = 0,
        /** Missing events the graph refused to apply (ApplyOutcome.failed); retried next pass. */
        val failed: Long = 0,
    ) {
        operator fun plus(o: Report) =
            Report(
                windows + o.windows,
                sourceIds + o.sourceIds,
                missing + o.missing,
                extra + o.extra,
                vanished + o.vanished,
                failed + o.failed,
            )
    }

    /** How far a budgeted [reconcileUpTo] got: every `created_at` up to [reachedUntil] is reconciled. */
    data class Progress(
        val report: Report,
        val reachedUntil: Long,
    )

    suspend fun reconcile(
        since: Long,
        until: Long,
    ): Report = reconcileUpTo(since, until, Long.MAX_VALUE).report

    /**
     * [reconcile], stopping once about [budget] source ids have been diffed: leaf windows are
     * reconciled oldest first, and a leaf that starts with the budget spent is left for the next
     * call. The first leaf always runs, so a call always makes progress. This is what bounds a
     * sweep tick when a WIDE window (widened across empty years) turns out to hold the corpus.
     */
    suspend fun reconcileUpTo(
        since: Long,
        until: Long,
        budget: Long,
    ): Progress {
        if (until < since) return Progress(Report(), until)
        if (budget <= 0) return Progress(Report(), if (since == Long.MIN_VALUE) since else since - 1)
        // The graph is listed FIRST. Listed after the source, an event the source acked and the
        // feed applied between the two listings would look EXTRA and be unapplied (and fenced).
        // In this order the race goes the harmless way: an event applied after the graph listing
        // looks missing and is applied again (a no-op).
        // Both listings are capped at maxWindowIds, so memory stays bounded whichever side is big.
        val graphIds = HashSet<String>()
        var graphTooMany = false
        graph.visitIds(since, until) { page ->
            page.forEach { graphIds += it.id }
            if (graphIds.size > maxWindowIds && until > since) {
                graphTooMany = true
                false
            } else {
                true
            }
        }
        if (graphTooMany) return split(since, until, budget)
        val sourceIds = collectSource(since, until) ?: return split(since, until, budget)

        val missing = sourceIds.filter { it !in graphIds }
        val extra = graphIds.filter { it !in sourceIds }
        // Extras first, so a missing version's slot is already free of a stale occupant here.
        if (extra.isNotEmpty()) graph.unapply(extra)
        var applied = 0L
        var vanished = 0L
        var failed = 0L
        for (chunk in missing.chunked(fetchChunk)) {
            val events = source.fetch(chunk).filter { policy.admits(it.kind) }
            vanished += chunk.size - events.size
            if (events.isNotEmpty()) {
                val outcome = graph.apply(events, authoritative = true)
                applied += outcome.applied
                failed += outcome.failed.size
            }
        }
        return Progress(
            Report(
                windows = 1,
                sourceIds = sourceIds.size.toLong(),
                missing = applied,
                extra = extra.size.toLong(),
                vanished = vanished,
                failed = failed,
            ),
            until,
        )
    }

    /** Drops fence entries older than [olderThanSecs] (spec §6.3): the fence only has to outlive a delivery race. */
    suspend fun sweepFence(olderThanSecs: Long) = graph.sweepFence(olderThanSecs)

    /**
     * Repairs dropped removals: ids the feed could not deliver as removes. Those the source no
     * longer holds are unapplied; the rest are still held and stay.
     */
    suspend fun reconcileRemovals(ids: List<String>): Report {
        var extra = 0L
        for (chunk in ids.chunked(fetchChunk)) {
            val held = source.fetch(chunk).mapTo(HashSet()) { it.id }
            val gone = chunk.filter { it !in held }
            if (gone.isNotEmpty()) {
                graph.unapply(gone)
                extra += gone.size
            }
        }
        return Report(extra = extra)
    }

    // The source's admitted ids in the window, or null when there are more than maxWindowIds
    // (the caller splits). The policy filter is applied by fetching kinds lazily: ids alone carry
    // no kind, so an excluded kind's id is only known excluded once fetched — with the default
    // policy (exclude nothing) that never happens.
    private suspend fun collectSource(
        since: Long,
        until: Long,
    ): Set<String>? {
        val ids = HashSet<String>()
        var tooMany = false
        source.visitIds(since, until) { page: List<IdAndTime> ->
            page.forEach { ids += it.id }
            if (ids.size > maxWindowIds && until > since) {
                tooMany = true
                false
            } else {
                true
            }
        }
        if (tooMany) return null
        if (policy.excludedKinds.isEmpty()) return ids
        val admitted = HashSet<String>()
        for (chunk in ids.chunked(fetchChunk)) source.fetch(chunk).forEach { if (policy.admits(it.kind)) admitted += it.id }
        return admitted
    }

    private suspend fun split(
        since: Long,
        until: Long,
        budget: Long,
    ): Progress {
        // Overflow-safe for windows spanning Long.MIN..Long.MAX (the sweep's head and tail).
        val mid = since + ((until - since) ushr 1)
        val left = reconcileUpTo(since, mid, budget)
        val spent = left.report.sourceIds + left.report.windows
        if (left.reachedUntil < mid || spent >= budget) return left
        val right = reconcileUpTo(mid + 1, until, budget - spent)
        return Progress(left.report + right.report, right.reachedUntil)
    }
}
