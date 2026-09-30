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
import com.vitorpamplona.neo4j.eventstore.engine.HeldRef
import com.vitorpamplona.neo4j.eventstore.engine.schema.Derivation
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * Proves and repairs `graph == source` (spec §7.2), one `created_at` window at a time.
 *
 * Both sides are listed for the window and diffed as sets: an id the source holds and the graph
 * does not is MISSING (fetched and applied AUTHORITATIVELY, as of the fetch: it bypasses a fence
 * older than the fetch); an id the graph holds and the source does not is EXTRA (unapplied). A
 * missing version that a held incumbent out-ranks in its slot (the incumbent may sit in another
 * window) is applied once the source confirms it no longer holds that incumbent, which is then
 * removed as an extra. Windows split in half while either side holds more than [maxWindowIds] in
 * them, so memory stays bounded however the corpus is distributed (a 500M-id snapshot cannot be
 * materialized).
 *
 * A held event whose derivation stamp is not the running build's ([Derivation]) is RE-DERIVED in
 * place: fetched and rewritten through [GraphIndex.rederive], within the call's budget.
 *
 * Races with the live feed are benign: a missing event still queued is applied twice (the
 * second is a no-op); an extra removed ahead of its own delivery is fenced; an event the feed
 * removes after the fetch stays fenced, since the fence is newer than the fetch; a version the
 * feed supersedes after the fetch keeps its slot, since the source holds it.
 */
class MirrorReconciler(
    private val source: SourceOfTruth,
    private val graph: GraphIndex,
    private val policy: GraphPolicy = GraphPolicy.Default,
    private val maxWindowIds: Int = 250_000,
    private val fetchChunk: Int = 500,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // The stamp the graph's own deriver writes (GraphProjection builds both from one policy).
    private val stamp = Derivation.stamp(policy)

    /** What one reconcile call found and fixed. */
    data class Report(
        val windows: Int = 0,
        val sourceIds: Long = 0,
        val missing: Long = 0,
        val extra: Long = 0,
        val vanished: Long = 0,
        /** Events the graph refused to apply or re-derive (ApplyOutcome.failed). */
        val failed: Long = 0,
        /** Held events re-derived because an older derivation wrote them. */
        val rederived: Long = 0,
        /**
         * The `created_at` of every [failed] event: work still OWED. [ReconcileLoop] marks their
         * hours dirty (with backoff); a caller driving this reconciler directly must retry them.
         */
        val failedCreatedAt: List<Long> = emptyList(),
        /** Dirty hours or stages that threw and were put back for a later tick ([ReconcileLoop]). */
        val errors: Long = 0,
    ) {
        operator fun plus(o: Report) =
            Report(
                windows + o.windows,
                sourceIds + o.sourceIds,
                missing + o.missing,
                extra + o.extra,
                vanished + o.vanished,
                failed + o.failed,
                rederived + o.rederived,
                if (o.failedCreatedAt.isEmpty()) failedCreatedAt else failedCreatedAt + o.failedCreatedAt,
                errors + o.errors,
            )

        /** What this work spent of a sweep budget: ids diffed, windows listed, events re-derived. */
        val cost: Long get() = sourceIds + windows + rederived
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
     * [reconcile], stopping once about [budget] has been spent ([Report.cost]): leaf windows are
     * reconciled oldest first, and a leaf that starts with the budget spent is left for the next
     * call. The first leaf always runs, so a call always makes progress. This is what bounds a
     * sweep tick when a WIDE window (widened across empty years) turns out to hold the corpus —
     * and what paces re-derivation after a derivation change, when every held event is stale.
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
        val stale = ArrayList<HeldRef>()
        var graphTooMany = false
        graph.visitIds(since, until) { page ->
            for (ref in page) {
                graphIds += ref.id
                if (ref.derived != stamp) stale += ref
            }
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
        var removed = extra.size.toLong()
        var vanished = 0L
        val failedAt = ArrayList<Long>()
        for (chunk in missing.chunked(fetchChunk)) {
            // Taken BEFORE the fetch: what the fetch returns was held at least until this second,
            // so a fence stamped from here on is a removal the fetch may not have seen.
            val asOf = nowSecs()
            val events = source.fetch(chunk).filter { policy.admits(it.kind) }
            vanished += chunk.size - events.size
            if (events.isEmpty()) continue
            val outcome = applyAuthoritatively(events, asOf)
            applied += outcome.applied
            removed += outcome.removed
            outcome.failed.forEach { failedAt += it.createdAt }
        }

        // Re-derive what an older derivation wrote, oldest first, within what the diff left of
        // the budget — but at least half of it: a leaf's diff can use the whole budget by
        // itself, and the resumed call re-diffs from the cut, so a smaller share would crawl.
        // Whole seconds only, so the resume point (the next stale event's second) never skips
        // one; and at least the first second, so a call always makes progress.
        val staleHeld = stale.filter { it.id in sourceIds }
        val allowance = maxOf(budget - sourceIds.size - 1, budget / 2)
        var take = minOf(staleHeld.size.toLong(), maxOf(allowance, 1L)).toInt()
        while (take in 1 until staleHeld.size && staleHeld[take].createdAt == staleHeld[take - 1].createdAt) take++
        var rederived = 0L
        for (chunk in staleHeld.subList(0, take).chunked(fetchChunk)) {
            val events = source.fetch(chunk.map { it.id }).filter { policy.admits(it.kind) }
            if (events.isEmpty()) continue
            val outcome = graph.rederive(events)
            rederived += outcome.applied
            outcome.failed.forEach { failedAt += it.createdAt }
        }
        val reachedUntil = if (take < staleHeld.size) staleHeld[take].createdAt - 1 else until

        return Progress(
            Report(
                windows = 1,
                sourceIds = sourceIds.size.toLong(),
                missing = applied,
                extra = removed,
                vanished = vanished,
                failed = failedAt.size.toLong(),
                rederived = rederived,
                failedCreatedAt = failedAt,
            ),
            reachedUntil,
        )
    }

    private class Applied(
        val applied: Long,
        val removed: Long,
        val failed: List<Event>,
    )

    /**
     * Applies [events] (held by the source as of [asOf]). An incumbent that out-ranks one of them
     * is asked about: if the source no longer holds it, it is an extra — unapplied, and the event
     * applied again. After a bulk load one slot can hold several versions, so this repeats a few
     * rounds; whatever is left is the next pass's.
     */
    private suspend fun applyAuthoritatively(
        events: List<Event>,
        asOf: Long,
    ): Applied {
        var applied = 0L
        var removed = 0L
        val failed = ArrayList<Event>()
        var pending = events
        repeat(MAX_OUTRANK_ROUNDS) {
            val outcome = graph.apply(pending, authoritativeAsOf = asOf)
            applied += outcome.applied
            failed += outcome.failed
            if (outcome.outranked.isEmpty()) return Applied(applied, removed, failed)
            val incumbents = outcome.outranked.values.distinct()
            val stillHeld = source.fetch(incumbents).mapTo(HashSet()) { it.id }
            val dropped = incumbents.filter { it !in stillHeld }.toSet()
            if (dropped.isEmpty()) return Applied(applied, removed, failed)
            graph.unapply(dropped.toList())
            removed += dropped.size
            pending = pending.filter { outcome.outranked[it.id] in dropped }
        }
        return Applied(applied, removed, failed)
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
    // (the caller splits). With the default policy (exclude nothing) every id is admitted. With
    // exclusions, a listed kind decides without a read; an id listed without its kind (a source
    // that cannot say it, SourceOfTruth.visitRefs) is only known excluded once fetched.
    private suspend fun collectSource(
        since: Long,
        until: Long,
    ): Set<String>? {
        val ids = HashSet<String>()
        val unknownKind = ArrayList<String>()
        var tooMany = false
        val fits = { ids.size + unknownKind.size <= maxWindowIds || until <= since }
        if (policy.excludedKinds.isEmpty()) {
            source.visitIds(since, until) { page ->
                page.forEach { ids += it.id }
                fits().also { if (!it) tooMany = true }
            }
        } else {
            source.visitRefs(since, until) { page ->
                for (ref in page) {
                    val kind = ref.kind
                    if (kind == null) {
                        unknownKind += ref.id
                    } else if (policy.admits(kind)) {
                        ids += ref.id
                    }
                }
                fits().also { if (!it) tooMany = true }
            }
        }
        if (tooMany) return null
        for (chunk in unknownKind.chunked(fetchChunk)) source.fetch(chunk).forEach { if (policy.admits(it.kind)) ids += it.id }
        return ids
    }

    private suspend fun split(
        since: Long,
        until: Long,
        budget: Long,
    ): Progress {
        // Overflow-safe for windows spanning Long.MIN..Long.MAX (the sweep's head and tail).
        val mid = since + ((until - since) ushr 1)
        val left = reconcileUpTo(since, mid, budget)
        val spent = left.report.cost
        if (left.reachedUntil < mid || spent >= budget) return left
        val right = reconcileUpTo(mid + 1, until, budget - spent)
        return Progress(left.report + right.report, right.reachedUntil)
    }

    companion object {
        // One round per extra version held in one slot; more than a couple only after a bulk load.
        private const val MAX_OUTRANK_ROUNDS = 4
    }
}
