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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 *    before the first Nostr event cost a few queries instead of one tick per day.
 */
class ReconcileLoop(
    private val reconciler: MirrorReconciler,
    private val dirty: DirtyTracker,
    private val cursor: CursorStore? = null,
    private val recentWindowSeconds: Long = 2 * 3_600,
    private val sweepStepSeconds: Long = 86_400,
    private val sweepIdsPerTick: Long = 250_000,
    private val sweepStart: Long = 0,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    @Volatile var last: MirrorReconciler.Report = MirrorReconciler.Report()
        private set

    @Volatile var lastTickAt: Long = 0
        private set

    @Volatile var sweepCursor: Long = cursor?.load() ?: sweepStart
        private set

    /** One pass of all three stages. */
    suspend fun tick(): MirrorReconciler.Report {
        var report = MirrorReconciler.Report()
        for (hour in dirty.drainHours()) report += reconciler.reconcile(hour, hour + DirtyTracker.HOUR - 1)
        dirty.drainRemovals().takeIf { it.isNotEmpty() }?.let { report += reconciler.reconcileRemovals(it) }

        val now = nowSecs()
        report += reconciler.reconcile(now - recentWindowSeconds, now + FUTURE_SLACK)

        var spent = 0L
        var step = sweepStepSeconds
        while (spent < sweepIdsPerTick) {
            val from = sweepCursor
            val to = minOf(from + step - 1, now)
            val window = reconciler.reconcile(from, to)
            report += window
            spent += window.sourceIds + 1 // +1: an empty window still costs its queries
            step = if (window.sourceIds == 0L && window.extra == 0L) minOf(step * 4, MAX_EMPTY_STEP) else sweepStepSeconds
            val wrapped = to >= now
            sweepCursor = if (wrapped) sweepStart else to + 1
            cursor?.save(sweepCursor)
            if (wrapped) break
        }

        last = report
        lastTickAt = now
        return report
    }

    fun start(
        scope: CoroutineScope,
        everySeconds: Long = 300,
        onError: (Throwable) -> Unit = {},
    ): Job =
        scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure(onError)
                delay(everySeconds * 1000)
            }
        }

    companion object {
        // Events are accepted with created_at a little in the future (clock skew); the recent
        // pass reaches past now so those are not left to the full sweep.
        const val FUTURE_SLACK = 15 * 60L

        // Ten years: a window this wide that DOES hold data is still bounded — the reconciler splits
        // any window holding more than its maxWindowIds.
        const val MAX_EMPTY_STEP = 10 * 365L * 86_400
    }
}
