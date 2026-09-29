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

import com.vitorpamplona.neo4j.eventstore.engine.ApplyOutcome
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.reconcile.DirtyTracker
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphFeedTest {
    @Test
    fun theFeedAppliesInOrderAndCoalescesBatches() =
        runTest {
            val graph = InMemoryGraphIndex()
            val feed = GraphFeed(graph, DirtyTracker())
            val corpus = GraphCorpus(3)
            val events = (0 until 40).map { corpus.next() }
            events.forEach { feed.onPut(listOf(it)) }
            feed.onRemove(events.take(5).map { it.id })
            feed.close()
            feed.start(this).join()

            val held = HashSet<String>()
            graph.visitIds(0, Long.MAX_VALUE) { page ->
                page.forEach { held += it.id }
                true
            }
            assertTrue(events.take(5).none { it.id in held }, "removes after puts took effect")
            assertEquals(41L, feed.stats().queued)
        }

    @Test
    fun aFullQueueDropsAndMarksDirtyInsteadOfBlocking() {
        val dirty = DirtyTracker()
        val feed = GraphFeed(InMemoryGraphIndex(), dirty, capacity = 2)
        val corpus = GraphCorpus(4)
        repeat(5) { feed.onPut(listOf(corpus.next())) }
        feed.onRemove(listOf("a".repeat(64)))
        assertEquals(3L + 1, feed.stats().dropped)
        assertTrue(dirty.pendingHours() >= 1)
        assertEquals(1, dirty.pendingRemovals())
    }

    @Test
    fun theQueueIsBoundedInEventsNotCalls() {
        val dirty = DirtyTracker()
        val feed = GraphFeed(InMemoryGraphIndex(), dirty, capacity = 10)
        val corpus = GraphCorpus(5)
        feed.onPut((0 until 11).map { corpus.next() })
        assertEquals(11L, feed.stats().dropped, "one call of 11 events does not fit 10")
        feed.onPut((0 until 10).map { corpus.next() })
        assertEquals(1L, feed.stats().queued)
        assertEquals(10L, feed.stats().pendingItems)
    }

    @Test
    fun closeAndDrainAppliesWhatIsQueued() =
        runTest {
            val graph = InMemoryGraphIndex()
            val feed = GraphFeed(graph, DirtyTracker())
            val corpus = GraphCorpus(6)
            val events = (0 until 30).map { corpus.next() }
            feed.start(this)
            events.forEach { feed.onPut(listOf(it)) }
            feed.closeAndDrain(5_000)
            val held = HashSet<String>()
            graph.visitIds(0, Long.MAX_VALUE) { page ->
                page.forEach { held += it.id }
                true
            }
            assertTrue(held.isNotEmpty() && feed.stats().pendingItems == 0L)
        }

    @Test
    fun eventsTheGraphRefusesAreMarkedDirty() =
        runTest {
            val dirty = DirtyTracker()
            val refusing =
                object : GraphIndex by InMemoryGraphIndex() {
                    override suspend fun apply(
                        events: List<Event>,
                        authoritative: Boolean,
                    ) = ApplyOutcome(failed = events)
                }
            val feed = GraphFeed(refusing, dirty)
            feed.onPut(listOf(GraphCorpus(7).next()))
            feed.close()
            feed.start(this).join()
            assertEquals(1, dirty.pendingHours())
            assertEquals(1L, feed.stats().failures)
        }
}
