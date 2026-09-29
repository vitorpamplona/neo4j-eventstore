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
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.SIG
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.hex
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Reconcile shapes the audit of 2026-09-29 found wrong, one test each. */
class ReconcileRegressionsTest {
    private suspend fun GraphIndex.heldIds(): Set<String> {
        val out = HashSet<String>()
        visitIds(Long.MIN_VALUE, Long.MAX_VALUE) { p ->
            p.forEach { out += it.id }
            true
        }
        return out
    }

    private fun note(
        seed: String,
        createdAt: Long,
    ) = Event(hex(seed), hex("author"), createdAt, 1, emptyArray(), seed, SIG)

    @Test
    fun aWideWindowHoldingTheCorpusStopsAtTheTickBudget() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(21)
            repeat(2_000) { source.put(corpus.next()) }
            val graph = InMemoryGraphIndex()
            val now = 1_700_000_000L + 100_000
            val loop =
                ReconcileLoop(
                    MirrorReconciler(source, graph, maxWindowIds = 100),
                    DirtyTracker(),
                    recentWindowSeconds = 0,
                    sweepIdsPerTick = 300,
                    nowSecs = { now },
                )
            loop.tick()
            val afterOne = graph.heldIds().size
            assertTrue(afterOne in 1..500, "one tick reconciled $afterOne of ${source.held.size} ids, past its budget")
            var ticks = 1
            while (graph.heldIds() != source.held.keys && ticks < 50) {
                loop.tick()
                ticks++
            }
            assertEquals(source.held.keys, graph.heldIds(), "the sweep resumes where the budget stopped it")
        }

    @Test
    fun theSweepCoversTheFarFutureAndBeforeTheEpoch() =
        runTest {
            val source = SimulatedSource()
            val future = note("year 2100", 4_102_444_800L)
            val past = note("before 1970", -1_000L)
            source.put(future)
            source.put(past)
            val graph = InMemoryGraphIndex()
            val loop =
                ReconcileLoop(MirrorReconciler(source, graph), DirtyTracker(), recentWindowSeconds = 60, nowSecs = { 1_700_000_000L })
            repeat(3) { loop.tick() }
            assertEquals(setOf(future.id, past.id), graph.heldIds())
        }

    @Test
    fun anEventTheFeedAppliesDuringTheDiffIsNotUnapplied() =
        runTest {
            val inner = SimulatedSource()
            val graph = InMemoryGraphIndex()
            val late = note("arrives mid-diff", 1_700_000_050L)
            // Right after the source is listed, the feed stores (and applies) a new event: in the
            // old source-then-graph order the graph listing then held an id the source listing
            // lacked, and the event was unapplied as EXTRA.
            val racing =
                object : SourceOfTruth by inner {
                    override suspend fun visitIds(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<IdAndTime>) -> Boolean,
                    ) {
                        inner.visitIds(since, until, onPage)
                        if (inner.put(late)) graph.apply(listOf(late))
                    }
                }
            val report = MirrorReconciler(racing, graph).reconcile(1_700_000_000L, 1_700_000_100L)
            assertEquals(0L, report.extra)
            assertEquals(setOf(late.id), graph.heldIds())
        }

    @Test
    fun aFailingTickPutsBackTheDirtyHoursItDidNotReach() =
        runTest {
            val failingHour = 7_200L
            val source =
                object : SourceOfTruth by SimulatedSource() {
                    override suspend fun visitIds(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<IdAndTime>) -> Boolean,
                    ) {
                        if (since == failingHour) error("vespa timed out")
                    }
                }
            val dirty = DirtyTracker()
            listOf(0L, 3_600L, 7_200L, 10_800L).forEach { dirty.markCreatedAt(it) }
            val loop = ReconcileLoop(MirrorReconciler(source, InMemoryGraphIndex()), dirty)
            assertFailsWith<IllegalStateException> { loop.tick() }
            assertEquals(listOf(7_200L, 10_800L), dirty.drainHours())
        }

    @Test
    fun theTickSweepsTheFence() =
        runTest {
            var now = 1_700_000_000L
            val graph = InMemoryGraphIndex(nowSecs = { now })
            val e = note("removed once", now)
            graph.unapply(listOf(e.id))
            assertEquals(1, graph.apply(listOf(e)).fenced)
            now += 3 * 3_600
            ReconcileLoop(MirrorReconciler(SimulatedSource(), graph), DirtyTracker(), nowSecs = { now }).tick()
            // Past the fence window it would apply either way; what the sweep removes is the entry.
            val fenceBefore = InMemoryGraphIndex(nowSecs = { now }, fenceSeconds = Long.MAX_VALUE / 4)
            fenceBefore.unapply(listOf(e.id))
            ReconcileLoop(
                MirrorReconciler(SimulatedSource(), fenceBefore),
                DirtyTracker(),
                fenceRetentionSeconds = 0,
                nowSecs = { now + 10 },
            ).tick()
            assertEquals(1, fenceBefore.apply(listOf(e)).applied, "a swept fence entry no longer blocks")
        }

    @Test
    fun dirtyWorkSurvivesARestart() {
        val file = File.createTempFile("dirty", ".txt").apply { deleteOnExit() }
        val a = DirtyTracker()
        a.markCreatedAt(1_700_000_123L)
        a.markRemovals(listOf("x".repeat(64)))
        a.save(file)
        val b = DirtyTracker()
        b.load(file)
        assertEquals(listOf(1_699_999_200L), b.drainHours())
        assertEquals(listOf("x".repeat(64)), b.drainRemovals())
    }
}
