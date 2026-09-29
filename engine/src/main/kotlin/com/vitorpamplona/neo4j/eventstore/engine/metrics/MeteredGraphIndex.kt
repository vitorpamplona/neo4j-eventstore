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
package com.vitorpamplona.neo4j.eventstore.engine.metrics

import com.vitorpamplona.neo4j.eventstore.engine.ApplyOutcome
import com.vitorpamplona.neo4j.eventstore.engine.EdgeView
import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Counts and times every [GraphIndex] call, per member, for the health surface (spec §9).
 * TRANSPARENCY IS THE CONTRACT: every member forwards to [inner] — `PortDecoratorsTest` fails a
 * decorator that leaves one to a default, since a default would answer instead of decorating.
 */
class MeteredGraphIndex(
    private val inner: GraphIndex,
) : GraphIndex {
    class Meter {
        val calls = AtomicLong()
        val nanos = AtomicLong()
        val failures = AtomicLong()
    }

    private val meters = ConcurrentHashMap<String, Meter>()

    private suspend inline fun <T> timed(
        name: String,
        block: () -> T,
    ): T {
        val meter = meters.getOrPut(name) { Meter() }
        val started = System.nanoTime()
        try {
            return block()
        } catch (e: CancellationException) {
            // A cancelled caller (shutdown, a closed scope) is not a failing graph.
            throw e
        } catch (e: Exception) {
            meter.failures.incrementAndGet()
            throw e
        } finally {
            meter.calls.incrementAndGet()
            meter.nanos.addAndGet(System.nanoTime() - started)
        }
    }

    /** name → (calls, total millis, failures). */
    fun snapshot(): Map<String, Triple<Long, Long, Long>> =
        meters.mapValues { (_, m) ->
            Triple(m.calls.get(), m.nanos.get() / 1_000_000, m.failures.get())
        }

    override suspend fun apply(
        events: List<Event>,
        authoritative: Boolean,
    ): ApplyOutcome = timed("apply") { inner.apply(events, authoritative) }

    override suspend fun unapply(ids: List<String>) = timed("unapply") { inner.unapply(ids) }

    override suspend fun visitIds(
        since: Long,
        until: Long,
        pageSize: Int,
        onPage: suspend (List<IdAndTime>) -> Boolean,
    ) = timed("visitIds") { inner.visitIds(since, until, pageSize, onPage) }

    override suspend fun edgesOf(id: String): List<EdgeView>? = timed("edgesOf") { inner.edgesOf(id) }

    override suspend fun sweepFence(olderThanSecs: Long) = timed("sweepFence") { inner.sweepFence(olderThanSecs) }

    override suspend fun dump(): GraphDump = timed("dump") { inner.dump() }

    override fun close() = inner.close()
}
