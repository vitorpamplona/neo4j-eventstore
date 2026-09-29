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

import java.util.concurrent.ConcurrentHashMap

/**
 * What the live feed could not deliver, for the reconciler to repair first (spec §6.2).
 *
 * A dropped PUT is remembered by its `created_at` HOUR: the reconciler re-diffs that hour. A
 * dropped REMOVE carries no timestamp, so its ids are kept as-is: the reconciler asks the source
 * whether each is still stored and unapplies the ones that are not.
 */
class DirtyTracker(
    private val maxRemovals: Int = 1_000_000,
) {
    private val hours = ConcurrentHashMap.newKeySet<Long>()
    private val removals = ConcurrentHashMap.newKeySet<String>()

    @Volatile var overflowed: Boolean = false
        private set

    fun markCreatedAt(createdAt: Long) {
        hours.add(Math.floorDiv(createdAt, HOUR) * HOUR)
    }

    fun markRemovals(ids: Collection<String>) {
        if (removals.size + ids.size > maxRemovals) {
            // Too many to remember: the full sweep will find them as "extras" instead.
            overflowed = true
            return
        }
        removals.addAll(ids)
    }

    /** Takes (and clears) the dirty hour starts, oldest first. */
    fun drainHours(): List<Long> = hours.toList().sorted().also { hours.removeAll(it.toSet()) }

    /** Takes (and clears) the dropped-removal ids. */
    fun drainRemovals(): List<String> = removals.toList().also { removals.removeAll(it.toSet()) }

    fun pendingHours() = hours.size

    fun pendingRemovals() = removals.size

    companion object {
        const val HOUR = 3_600L
    }
}
