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
package com.vitorpamplona.neo4j.eventstore.feed

import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.reconcile.DirtyTracker
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * What the source's write path tells the projection: every ACKED physical put and remove
 * (vespa-eventstore's `IndexObserver` has exactly this shape; vespa-relay adapts one to the
 * other). Implementations must not block or throw — they run inside the source's write path.
 */
interface ChangeListener {
    fun onPut(events: List<Event>)

    fun onRemove(ids: List<String>)

    /** A source write that FAILED: each event may or may not be stored now, each id may or may not be removed. */
    fun onUncertain(
        events: List<Event>,
        ids: List<String>,
    )
}

/**
 * The live half of the projection (spec §6.2): the source's write path ENQUEUES and returns;
 * one consumer drains into the [GraphIndex] in order.
 *
 * The source's latency never depends on the graph: the queue is bounded — in EVENTS and ids, not
 * in calls, since one call can be a sync batch of thousands — and an entry that does not fit is
 * DROPPED and marked in [dirty] — a put by its `created_at` hour, a remove by its id — for the
 * reconciler to repair first. A graph write that fails, or an event the graph refuses, is marked
 * the same way. There is no durable outbox: the reconciler is the durability mechanism, and it is
 * needed anyway.
 */
class GraphFeed(
    private val graph: GraphIndex,
    private val dirty: DirtyTracker,
    private val policy: GraphPolicy = GraphPolicy.Default,
    capacity: Int = DEFAULT_CAPACITY,
    private val maxBatch: Int = DEFAULT_BATCH,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ChangeListener {
    private sealed interface Op {
        val enqueuedAt: Long

        class Put(
            val events: List<Event>,
            override val enqueuedAt: Long,
        ) : Op

        class Remove(
            val ids: List<String>,
            override val enqueuedAt: Long,
        ) : Op
    }

    // Unlimited as a channel; [capacity] is enforced in items by [pendingItems].
    private val queue = Channel<Op>(Channel.UNLIMITED)
    private val maxItems = capacity.toLong()
    private val pendingItems = AtomicLong()
    private var consumer: Job? = null

    private val queued = AtomicLong()
    private val dropped = AtomicLong()
    private val appliedEvents = AtomicLong()
    private val removedIds = AtomicLong()
    private val failures = AtomicLong()

    @Volatile private var oldestInFlight: Long = 0

    // The batch the consumer is applying, for a drain that gives up on it (closeAndDrain).
    @Volatile private var inFlight: Op? = null

    override fun onPut(events: List<Event>) {
        val admitted = if (policy.excludedKinds.isEmpty()) events else events.filter { policy.admits(it.kind) }
        if (admitted.isEmpty()) return
        if (reserve(admitted.size) && queue.trySend(Op.Put(admitted, nowMillis())).isSuccess) {
            queued.incrementAndGet()
        } else {
            dropped.addAndGet(admitted.size.toLong())
            admitted.forEach { dirty.markCreatedAt(it.createdAt) }
        }
    }

    override fun onRemove(ids: List<String>) {
        if (ids.isEmpty()) return
        if (reserve(ids.size) && queue.trySend(Op.Remove(ids, nowMillis())).isSuccess) {
            queued.incrementAndGet()
        } else {
            dropped.addAndGet(ids.size.toLong())
            dirty.markRemovals(ids)
        }
    }

    // Claims room for [n] items; false (and nothing claimed) when they do not fit. A send that then
    // fails (a closed channel) leaves the claim — harmless, the feed is closing.
    private fun reserve(n: Int): Boolean {
        if (pendingItems.addAndGet(n.toLong()) <= maxItems) return true
        pendingItems.addAndGet(-n.toLong())
        return false
    }

    private fun Op.size() =
        when (this) {
            is Op.Put -> events.size
            is Op.Remove -> ids.size
        }

    private fun markDirty(op: Op) {
        when (op) {
            is Op.Put -> op.events.forEach { dirty.markCreatedAt(it.createdAt) }
            is Op.Remove -> dirty.markRemovals(op.ids)
        }
    }

    private fun receiveNow(): Op? = queue.tryReceive().getOrNull()?.also { pendingItems.addAndGet(-it.size().toLong()) }

    // Nothing to apply — which part landed is unknown — so it is owed to the reconciler, which
    // asks the source.
    override fun onUncertain(
        events: List<Event>,
        ids: List<String>,
    ) {
        events.forEach { if (policy.admits(it.kind)) dirty.markCreatedAt(it.createdAt) }
        if (ids.isNotEmpty()) dirty.markRemovals(ids)
    }

    /** Starts the single consumer. Order within this feed is preserved. */
    fun start(scope: CoroutineScope): Job {
        check(consumer == null) { "already started" }
        return scope
            .launch {
                var pending: Op? = null
                try {
                    while (true) {
                        val first =
                            pending ?: queue.receiveCatching().getOrNull()?.also { pendingItems.addAndGet(-it.size().toLong()) } ?: break
                        pending = null
                        oldestInFlight = first.enqueuedAt
                        // Coalesce consecutive ops of the same kind into one graph batch.
                        when (first) {
                            is Op.Put -> {
                                val batch = ArrayList(first.events)
                                while (batch.size < maxBatch) {
                                    val next = receiveNow() ?: break
                                    if (next !is Op.Put) {
                                        pending = next
                                        break
                                    }
                                    batch += next.events
                                }
                                inFlight = Op.Put(batch, first.enqueuedAt)
                                runOrMarkDirty({ batch.forEach { dirty.markCreatedAt(it.createdAt) } }) {
                                    val outcome = graph.apply(batch)
                                    appliedEvents.addAndGet(outcome.applied.toLong())
                                    if (outcome.failed.isNotEmpty()) {
                                        failures.addAndGet(outcome.failed.size.toLong())
                                        outcome.failed.forEach { dirty.markCreatedAt(it.createdAt) }
                                    }
                                }
                            }

                            is Op.Remove -> {
                                val batch = ArrayList(first.ids)
                                while (batch.size < maxBatch) {
                                    val next = receiveNow() ?: break
                                    if (next !is Op.Remove) {
                                        pending = next
                                        break
                                    }
                                    batch += next.ids
                                }
                                inFlight = Op.Remove(batch, first.enqueuedAt)
                                runOrMarkDirty({ dirty.markRemovals(batch) }) {
                                    graph.unapply(batch)
                                    removedIds.addAndGet(batch.size.toLong())
                                }
                            }
                        }
                        inFlight = null
                        oldestInFlight = 0
                    }
                } finally {
                    // Cancelled mid-coalesce: the op read ahead is in no batch yet.
                    pending?.let { markDirty(it) }
                }
            }.also { consumer = it }
    }

    private suspend fun runOrMarkDirty(
        onFailure: () -> Unit,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            onFailure()
            throw e
        } catch (e: Exception) {
            failures.incrementAndGet()
            onFailure()
        }
    }

    /** Stops accepting; the consumer drains what is queued, then ends. */
    fun close() {
        queue.close()
    }

    /**
     * [close], then waits up to [timeoutMillis] for the consumer to apply what is queued. What it
     * cannot finish in time is marked dirty (to be saved by the caller) instead of vanishing
     * with the process.
     *
     * BOUNDED even when the graph hangs: the consumer is cancelled, but a graph call blocked in
     * the driver (retrying against a server that is down) only sees that between events, so it
     * gets [cancelGraceMillis] more and is then abandoned — its batch marked dirty here, which is
     * idempotent with the consumer marking it again when it does unwind.
     */
    suspend fun closeAndDrain(
        timeoutMillis: Long,
        cancelGraceMillis: Long = 1_000,
    ) {
        queue.close()
        val job = consumer ?: return
        if (withTimeoutOrNull(timeoutMillis) { job.join() } == null) {
            job.cancel()
            withTimeoutOrNull(cancelGraceMillis) { job.join() }
            inFlight?.let { markDirty(it) }
            while (true) markDirty(receiveNow() ?: break)
        }
    }

    /** Gauges for the relay's health surface (spec §9). */
    fun stats() =
        FeedStats(
            queued = queued.get(),
            dropped = dropped.get(),
            appliedEvents = appliedEvents.get(),
            removedIds = removedIds.get(),
            failures = failures.get(),
            lagMillis = oldestInFlight.let { if (it == 0L) 0 else nowMillis() - it },
            pendingItems = pendingItems.get(),
            dirtyHours = dirty.pendingHours().toLong(),
            dirtyRemovals = dirty.pendingRemovals().toLong(),
            removalsOverflowed = dirty.overflowed,
        )

    companion object {
        /** Queued events + removal ids. */
        const val DEFAULT_CAPACITY = 100_000
        const val DEFAULT_BATCH = 500
    }
}

data class FeedStats(
    val queued: Long,
    val dropped: Long,
    val appliedEvents: Long,
    val removedIds: Long,
    val failures: Long,
    val lagMillis: Long,
    /** Events + ids waiting in the queue now. */
    val pendingItems: Long = 0,
    /** Work the reconciler owes: hours to re-diff, removal ids to re-check. */
    val dirtyHours: Long = 0,
    val dirtyRemovals: Long = 0,
    /** Removal ids were dropped for want of room (left to the full sweep). */
    val removalsOverflowed: Boolean = false,
)
