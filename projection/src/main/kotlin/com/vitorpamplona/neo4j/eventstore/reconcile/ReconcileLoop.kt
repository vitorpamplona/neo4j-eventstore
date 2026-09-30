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

import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Where the full sweep remembers how far it got, so a restart resumes instead of starting over. */
interface CursorStore {
    fun load(): Long?

    fun save(createdAt: Long)
}

class FileCursorStore(
    private val file: File,
) : CursorStore {
    override fun load(): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    override fun save(createdAt: Long) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(createdAt.toString())
        tmp.renameTo(file) // atomic replace: a crash never leaves a torn cursor
    }
}

/**
 * The reconciler's cadence (spec §7.2), cheapest-and-most-likely first:
 * 1. what the feed DROPPED (dirty hours, dropped removals);
 * 2. a rolling RECENT pass over the last [recentWindowSeconds];
 * 3. the FULL sweep over the whole corpus, resumable through [cursor], wrapping around at now.
 *    Each tick spends a budget of [sweepIdsPerTick] source ids, not a fixed span of time: a
 *    window with nothing in it widens the next one (×4, up to ten years), so the empty decades
 *    before the first Nostr event cost a few queries instead of one tick per day — and a wide
 *    window that DOES hold data stops at the budget too ([MirrorReconciler.reconcileUpTo]), with
 *    the cursor saved where it stopped. Each wrap also covers what lies outside
 *    [sweepStart]..now: negative timestamps and the far future (the corpus has notes dated 2100).
 * 4. the fence sweep: `:Removed` entries older than [fenceRetentionSeconds].
 *
 * Every stage also re-derives the held events an older derivation wrote (`Derivation`); the full
 * sweep's budget paces that, counting each re-derived event against it.
 *
 * What a stage could not do is OWED, never lost: an event the graph refuses marks its hour dirty
 * with backoff, and a dirty hour that throws is put back (with backoff) while the tick goes on
 * with the other hours and stages ([MirrorReconciler.Report.errors], [lastError]).
 *
 * A process that only FEEDS the graph (vespa-relay's sync) runs [tick] with `dirtyOnly`: its own
 * dropped writes are repaired by itself, since its tracker is in its own memory.
 */
class ReconcileLoop(
    private val reconciler: MirrorReconciler,
    private val dirty: DirtyTracker,
    private val cursor: CursorStore? = null,
    private val recentWindowSeconds: Long = 2 * 3_600,
    private val sweepStepSeconds: Long = 86_400,
    private val sweepIdsPerTick: Long = 250_000,
    private val sweepStart: Long = 0,
    private val fenceRetentionSeconds: Long = 2 * InMemoryGraphIndex.DEFAULT_FENCE_SECONDS,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    @Volatile var last: MirrorReconciler.Report = MirrorReconciler.Report()
        private set

    @Volatile var lastTickAt: Long = 0
        private set

    @Volatile var sweepCursor: Long = cursor?.load() ?: sweepStart
        private set

    /** The last failure a tick recovered from (a dirty hour or the removals that threw and were put back). */
    @Volatile var lastError: Throwable? = null
        private set

    // The dirty tracker's overflow count when the current sweep pass began; null until a pass
    // begins in this process (a restart mid-pass cannot vouch for the part already swept).
    private var passStartEpoch: Long? = null

    @Volatile private var job: Job? = null

    /**
     * One pass of every stage (or only the first, with [dirtyOnly]). [onError] hears each failure
     * the tick recovers from; a failure of the recent pass or the sweep still throws.
     */
    suspend fun tick(
        dirtyOnly: Boolean = false,
        onError: (Throwable) -> Unit = {},
    ): MirrorReconciler.Report {
        val now = nowSecs()
        // Hours already put back with backoff this tick: one tick is one attempt, however many
        // of its stages met the same poison event.
        val owed = HashSet<Long>()
        var report = MirrorReconciler.Report()
        val sweepFailed = ArrayList<Long>() // the sweep's, as it goes: it saves its cursor per window
        try {
            report += repairDirty(now, owed, onError)
            if (dirtyOnly) {
                last = report
                lastTickAt = now
                return report
            }
            report += reconciler.reconcile(now - recentWindowSeconds, now + FUTURE_SLACK)
            report += sweep(now, sweepFailed)
        } finally {
            // Also when a later stage throws: what the earlier ones were refused stays owed.
            for (createdAt in report.failedCreatedAt + sweepFailed) {
                if (owed.add(DirtyTracker.hourOf(createdAt))) dirty.markFailing(createdAt, now)
            }
        }
        reconciler.sweepFence(now - fenceRetentionSeconds)

        last = report
        lastTickAt = now
        return report
    }

    // Drained work that a failure interrupts is PUT BACK, or one timeout would lose every dirty
    // hour behind it to the full sweep. One hour that throws is put back with backoff and the
    // rest go on — a poison window must not block every other repair, tick after tick — but
    // several in a row mean the graph or the source is down: the rest go back untouched.
    private suspend fun repairDirty(
        now: Long,
        owed: MutableSet<Long>,
        onError: (Throwable) -> Unit,
    ): MirrorReconciler.Report {
        var report = MirrorReconciler.Report()
        val hours = dirty.drainHours(now)
        var consecutiveErrors = 0
        for ((i, hour) in hours.withIndex()) {
            if (consecutiveErrors >= MAX_CONSECUTIVE_HOUR_ERRORS) {
                for (j in i until hours.size) dirty.markHour(hours[j])
                break
            }
            try {
                val hourReport = reconciler.reconcile(hour, hour + DirtyTracker.HOUR - 1)
                // Its refused events, if any, are owed again (with backoff) by the tick.
                if (hourReport.failedCreatedAt.isEmpty()) dirty.settle(hour)
                report += hourReport
                consecutiveErrors = 0
            } catch (e: CancellationException) {
                for (j in i until hours.size) dirty.markHour(hours[j])
                throw e
            } catch (e: Throwable) {
                dirty.markFailing(hour, now)
                owed += hour
                report += MirrorReconciler.Report(errors = 1)
                consecutiveErrors++
                recovered(e, onError)
            }
        }
        val removals = dirty.drainRemovals()
        if (removals.isNotEmpty()) {
            try {
                report += reconciler.reconcileRemovals(removals)
            } catch (e: CancellationException) {
                dirty.markRemovals(removals)
                throw e
            } catch (e: Throwable) {
                dirty.markRemovals(removals) // idempotent: re-checking an already-repaired id is a no-op
                report += MirrorReconciler.Report(errors = 1)
                recovered(e, onError)
            }
        }
        return report
    }

    private fun recovered(
        e: Throwable,
        onError: (Throwable) -> Unit,
    ) {
        lastError = e
        runCatching { onError(e) }
    }

    private suspend fun sweep(
        now: Long,
        failed: MutableList<Long>,
    ): MirrorReconciler.Report {
        var report = MirrorReconciler.Report()
        var spent = 0L
        var step = sweepStepSeconds
        if (passStartEpoch == null && sweepCursor == sweepStart) passStartEpoch = dirty.overflowEpoch()
        while (spent < sweepIdsPerTick) {
            val from = sweepCursor
            val to = if (from > now - step) now else from + step - 1
            val progress = reconciler.reconcileUpTo(from, to, sweepIdsPerTick - spent)
            failed += progress.report.failedCreatedAt
            report += progress.report
            spent += progress.report.cost
            if (progress.reachedUntil < to) {
                // The budget ran out inside the window: resume right where it stopped.
                sweepCursor = progress.reachedUntil + 1
                cursor?.save(sweepCursor)
                break
            }
            val empty = progress.report.sourceIds == 0L && progress.report.extra == 0L
            step = if (empty) minOf(step * 4, MAX_EMPTY_STEP) else sweepStepSeconds
            if (to >= now) {
                // Wrapped. Before starting over, what the cursor never visits: created_at below
                // sweepStart, and anything past now (the recent pass reaches only FUTURE_SLACK).
                if (sweepStart > Long.MIN_VALUE) report += reconciler.reconcile(Long.MIN_VALUE, sweepStart - 1)
                report += reconciler.reconcile(now + 1, Long.MAX_VALUE)
                sweepCursor = sweepStart
                cursor?.save(sweepCursor)
                // A whole pass has seen every extra the removals that overflowed before it began
                // would have repaired; the next pass starts now.
                passStartEpoch?.let { dirty.clearOverflow(it) }
                passStartEpoch = dirty.overflowEpoch()
                break
            }
            sweepCursor = to + 1
            cursor?.save(sweepCursor)
        }
        return report
    }

    fun start(
        scope: CoroutineScope,
        everySeconds: Long = 300,
        dirtyOnly: Boolean = false,
        onError: (Throwable) -> Unit = {},
    ): Job {
        require(everySeconds >= 1) { "everySeconds must be >= 1, was $everySeconds" }
        return scope
            .launch {
                while (isActive) {
                    try {
                        tick(dirtyOnly, onError)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        onError(e)
                    }
                    delay(everySeconds * 1000)
                }
            }.also { job = it }
    }

    /**
     * Cancels the loop [start] launched, whatever scope it runs in, and waits up to
     * [timeoutMillis] for its tick to unwind: a cancelled tick puts back the dirty work it
     * drained, so the tracker is complete once this returns in time. `GraphProjection.close()`
     * calls it before saving the tracker. A [tick] the embedder drives itself is the embedder's
     * to stop first.
     */
    suspend fun stop(timeoutMillis: Long = 10_000) {
        val running = job ?: return
        running.cancel()
        withTimeoutOrNull(timeoutMillis) { running.join() }
    }

    companion object {
        // Events are accepted with created_at a little in the future (clock skew); the recent
        // pass reaches past now so those are not left to the full sweep.
        const val FUTURE_SLACK = 15 * 60L

        // Ten years: a window this wide that DOES hold data is still bounded — the reconciler splits
        // any window holding more than its maxWindowIds, and stops at the tick's id budget.
        const val MAX_EMPTY_STEP = 10 * 365L * 86_400

        // Dirty hours that throw back to back before the rest are put back for the next tick.
        const val MAX_CONSECUTIVE_HOUR_ERRORS = 3
    }
}
