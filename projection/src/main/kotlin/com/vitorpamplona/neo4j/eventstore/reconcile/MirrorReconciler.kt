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
 * half while the source holds more than [maxWindowIds] in them, so memory stays bounded however
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
    ) {
        operator fun plus(o: Report) =
            Report(
                windows + o.windows,
                sourceIds + o.sourceIds,
                missing + o.missing,
                extra + o.extra,
                vanished + o.vanished,
            )
    }

    suspend fun reconcile(
        since: Long,
        until: Long,
    ): Report {
        if (until < since) return Report()
        val sourceIds = collectSource(since, until) ?: return split(since, until)
        val graphIds = HashSet<String>()
        graph.visitIds(since, until) { page ->
            page.forEach { graphIds += it.id }
            true
        }

        val missing = sourceIds.filter { it !in graphIds }
        val extra = graphIds.filter { it !in sourceIds }
        // Extras first, so a missing version's slot is already free of a stale occupant here.
        if (extra.isNotEmpty()) graph.unapply(extra)
        var applied = 0L
        var vanished = 0L
        for (chunk in missing.chunked(fetchChunk)) {
            val events = source.fetch(chunk).filter { policy.admits(it.kind) }
            vanished += chunk.size - events.size
            if (events.isNotEmpty()) applied += graph.apply(events, authoritative = true).applied
        }
        return Report(windows = 1, sourceIds = sourceIds.size.toLong(), missing = applied, extra = extra.size.toLong(), vanished = vanished)
    }

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
    ): Report {
        val mid = since + (until - since) / 2
        return reconcile(since, mid) + reconcile(mid + 1, until)
    }
}
