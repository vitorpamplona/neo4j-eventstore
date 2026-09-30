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

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the live feed could not deliver, for the reconciler to repair first (spec §6.2).
 *
 * A dropped PUT is remembered by its `created_at` HOUR: the reconciler re-diffs that hour. A
 * dropped REMOVE carries no timestamp, so its ids are kept as-is: the reconciler asks the source
 * whether each is still stored and unapplies the ones that are not.
 *
 * An hour whose repair keeps FAILING (an event the graph refuses, a window that throws) is owed
 * with exponential backoff ([markFailing]): retried, but not every tick, so one poison event
 * neither spins the reconciler nor starves the hours behind it.
 */
class DirtyTracker(
    private val maxRemovals: Int = 1_000_000,
) {
    private val hours = ConcurrentHashMap.newKeySet<Long>()
    private val removals = ConcurrentHashMap.newKeySet<String>()

    // Failing hours: when each is next due, and how many times in a row it has failed.
    private val deferred = ConcurrentHashMap<Long, Long>()
    private val attempts = ConcurrentHashMap<Long, Int>()

    private val overflows = AtomicLong()

    /**
     * Set when removal ids had to be dropped for want of room. Those are left to the full sweep
     * (which finds them as extras); surfaced on the health surface so an operator knows, and
     * cleared by the sweep once a whole pass that started after the last overflow has wrapped
     * ([overflowEpoch], [clearOverflow]).
     */
    @Volatile var overflowed: Boolean = false
        private set

    fun markCreatedAt(createdAt: Long) {
        hours.add(hourOf(createdAt))
    }

    /** Puts back an hour start [drainHours] handed out (a reconcile that failed before reaching it). */
    fun markHour(hourStart: Long) {
        hours.add(hourStart)
    }

    /**
     * Owes the hour of [createdAt] again, after a backoff that doubles with each consecutive
     * failure of that hour ([BACKOFF_BASE_SECONDS], up to [BACKOFF_MAX_SECONDS]) until [settle].
     */
    fun markFailing(
        createdAt: Long,
        nowSecs: Long,
    ) {
        val hour = hourOf(createdAt)
        val n = attempts.merge(hour, 1, Int::plus) ?: 1
        deferred[hour] = nowSecs + backoffSeconds(n)
    }

    /** The hour starting at [hourStart] was repaired cleanly: its next failure starts the backoff over. */
    fun settle(hourStart: Long) {
        attempts.remove(hourStart)
    }

    /** Keeps as many of [ids] as there is room for; the rest are left to the full sweep ([overflowed]). */
    fun markRemovals(ids: Collection<String>) {
        if (removals.size + ids.size <= maxRemovals) {
            removals.addAll(ids)
            return
        }
        for (id in ids) {
            if (id in removals) continue
            if (removals.size >= maxRemovals) {
                overflow()
                return
            }
            removals.add(id)
        }
    }

    private fun overflow() =
        synchronized(this) {
            overflows.incrementAndGet()
            overflowed = true
        }

    /** A counter of overflows: the sweep notes it when a pass starts. */
    fun overflowEpoch(): Long = overflows.get()

    /** Clears [overflowed] if nothing overflowed since [epoch] — a sweep pass that started then has seen every extra. */
    fun clearOverflow(epoch: Long) =
        synchronized(this) {
            if (overflows.get() == epoch) overflowed = false
        }

    /**
     * Takes (and clears) the dirty hour starts due by [nowSecs], oldest first: every hour marked
     * dirty, plus the failing ones whose backoff has passed (all of them by default).
     */
    fun drainHours(nowSecs: Long = Long.MAX_VALUE): List<Long> {
        val due = deferred.entries.filter { it.value <= nowSecs }.map { it.key }
        due.forEach { deferred.remove(it) }
        val out = (hours.toList() + due).distinct().sorted()
        hours.removeAll(out.toSet())
        return out
    }

    /** Takes (and clears) the dropped-removal ids. */
    fun drainRemovals(): List<String> = removals.toList().also { removals.removeAll(it.toSet()) }

    fun pendingHours() = (hours + deferred.keys).size

    fun pendingRemovals() = removals.size

    /**
     * Writes what is pending to [file] (atomically), so a restart keeps it: the tracker is in
     * memory, and the live feed's drops are otherwise only repaired by the full sweep. A failing
     * hour is saved as a plain dirty hour (its backoff starts over).
     */
    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            if (overflowed) w.write("overflowed\n")
            (hours + deferred.keys).forEach { w.write("h $it\n") }
            removals.forEach { w.write("r $it\n") }
        }
        tmp.renameTo(file)
    }

    /** Adds what [save] wrote to [file], if it exists; a torn or foreign line is skipped. */
    fun load(file: File) {
        if (!file.exists()) return
        file.forEachLine { line ->
            when {
                line == "overflowed" -> overflow()
                line.startsWith("h ") -> line.substring(2).toLongOrNull()?.let { hours.add(it) }
                line.startsWith("r ") -> if (removals.size < maxRemovals) removals.add(line.substring(2)) else overflow()
            }
        }
    }

    companion object {
        const val HOUR = 3_600L

        /** A failing hour's first retry: about one reconcile tick. */
        const val BACKOFF_BASE_SECONDS = 300L

        /** A failing hour is still retried at least daily. */
        const val BACKOFF_MAX_SECONDS = 86_400L

        fun hourOf(createdAt: Long) = Math.floorDiv(createdAt, HOUR) * HOUR

        fun backoffSeconds(consecutiveFailures: Int): Long =
            minOf(BACKOFF_MAX_SECONDS, BACKOFF_BASE_SECONDS shl minOf(consecutiveFailures - 1, 20).coerceAtLeast(0))
    }
}
