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
import java.util.concurrent.atomic.AtomicLong

/**
 * What the source's write path tells the projection: every ACKED physical put and remove
 * (vespa-eventstore's `IndexObserver` has exactly this shape; vespa-relay adapts one to the
 * other). Implementations must not block or throw — they run inside the source's write path.
 */
interface ChangeListener {
    fun onPut(events: List<Event>)

    fun onRemove(ids: List<String>)
}

/**
 * The live half of the projection (spec §6.2): the source's write path ENQUEUES and returns;
 * one consumer drains into the [GraphIndex] in order.
 *
 * The source's latency never depends on the graph: the queue is bounded, and an entry that does
 * not fit is DROPPED and marked in [dirty] — a put by its `created_at` hour, a remove by its id —
 * for the reconciler to repair first. A graph write that fails is marked the same way. There is
 * no durable outbox: the reconciler is the durability mechanism, and it is needed anyway.
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

    private val queue = Channel<Op>(capacity)
    private var consumer: Job? = null

    private val queued = AtomicLong()
    private val dropped = AtomicLong()
    private val appliedEvents = AtomicLong()
    private val removedIds = AtomicLong()
    private val failures = AtomicLong()

    @Volatile private var oldestInFlight: Long = 0

    override fun onPut(events: List<Event>) {
        val admitted = if (policy.excludedKinds.isEmpty()) events else events.filter { policy.admits(it.kind) }
        if (admitted.isEmpty()) return
        if (queue.trySend(Op.Put(admitted, nowMillis())).isSuccess) {
            queued.incrementAndGet()
        } else {
            dropped.addAndGet(admitted.size.toLong())
            admitted.forEach { dirty.markCreatedAt(it.createdAt) }
        }
    }

    override fun onRemove(ids: List<String>) {
        if (ids.isEmpty()) return
        if (queue.trySend(Op.Remove(ids, nowMillis())).isSuccess) {
            queued.incrementAndGet()
        } else {
            dropped.addAndGet(ids.size.toLong())
            dirty.markRemovals(ids)
        }
    }

    /** Starts the single consumer. Order within this feed is preserved. */
    fun start(scope: CoroutineScope): Job {
        check(consumer == null) { "already started" }
        return scope
            .launch {
                var pending: Op? = null
                while (true) {
                    val first = pending ?: queue.receiveCatching().getOrNull() ?: break
                    pending = null
                    oldestInFlight = first.enqueuedAt
                    // Coalesce consecutive ops of the same kind into one graph batch.
                    when (first) {
                        is Op.Put -> {
                            val batch = ArrayList(first.events)
                            while (batch.size < maxBatch) {
                                val next = queue.tryReceive().getOrNull() ?: break
                                if (next !is Op.Put) {
                                    pending = next
                                    break
                                }
                                batch += next.events
                            }
                            runOrMarkDirty({ batch.forEach { dirty.markCreatedAt(it.createdAt) } }) {
                                appliedEvents.addAndGet(graph.apply(batch).applied.toLong())
                            }
                        }

                        is Op.Remove -> {
                            val batch = ArrayList(first.ids)
                            while (batch.size < maxBatch) {
                                val next = queue.tryReceive().getOrNull() ?: break
                                if (next !is Op.Remove) {
                                    pending = next
                                    break
                                }
                                batch += next.ids
                            }
                            runOrMarkDirty({ dirty.markRemovals(batch) }) {
                                graph.unapply(batch)
                                removedIds.addAndGet(batch.size.toLong())
                            }
                        }
                    }
                    oldestInFlight = 0
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

    /** Gauges for the relay's health surface (spec §9). */
    fun stats() =
        FeedStats(
            queued = queued.get(),
            dropped = dropped.get(),
            appliedEvents = appliedEvents.get(),
            removedIds = removedIds.get(),
            failures = failures.get(),
            lagMillis = oldestInFlight.let { if (it == 0L) 0 else nowMillis() - it },
        )

    companion object {
        const val DEFAULT_CAPACITY = 10_000
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
)
