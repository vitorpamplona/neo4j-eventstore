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

import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.Histories
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The projection's central property (spec §6–§7): whatever order the two writer feeds deliver
 * in, whatever they drop, and whether or not the source reports supersession removals, ONE
 * reconcile pass leaves the graph equal to a fresh projection of what the source holds.
 */
class MirrorReconcilerTest {
    private suspend fun GraphIndex.replay(
        changes: List<SimulatedSource.Change>,
        tick: () -> Unit,
    ) {
        for (c in changes) {
            when (c) {
                is SimulatedSource.Change.Put -> apply(listOf(c.event))
                is SimulatedSource.Change.Remove -> unapply(listOf(c.id))
            }
            tick()
        }
    }

    private fun assertSameGraph(
        expected: GraphDump,
        actual: GraphDump,
        message: String,
    ) {
        if (expected == actual) return
        val diff =
            buildString {
                appendLine(message)
                (expected.nodes - actual.nodes).take(5).forEach { appendLine("  missing node $it") }
                (actual.nodes - expected.nodes).take(5).forEach { appendLine("  extra node   $it") }
                (expected.edges - actual.edges).take(5).forEach { appendLine("  missing edge $it") }
                (actual.edges - expected.edges).take(5).forEach { appendLine("  extra edge   $it") }
            }
        fail(diff)
    }

    private suspend fun freshProjection(source: SimulatedSource) = InMemoryGraphIndex().apply { apply(source.held.values.toList()) }.dump()

    @Test
    fun oneReconcileConvergesAnyInterleavingWithDrops() =
        runTest {
            repeat(300) { seed ->
                val random = Random(seed)
                val source = SimulatedSource(reportSupersessionRemovals = random.nextBoolean())
                Histories.drive(source, GraphCorpus(seed), random, steps = 250)

                var now = 0L
                val graph = InMemoryGraphIndex(fenceSeconds = 60, nowSecs = { now })
                val (delivered, _) = Histories.deliveries(source.history, random, dropRate = 0.05)
                graph.replay(delivered) { now += random.nextLong(0, 2) }

                MirrorReconciler(source, graph, maxWindowIds = 40).reconcile(0, Long.MAX_VALUE / 2)
                assertSameGraph(freshProjection(source), graph.dump(), "seed $seed")
            }
        }

    @Test
    fun aSingleInOrderLosslessFeedIsExactWithoutReconciling() =
        runTest {
            repeat(200) { seed ->
                val random = Random(seed)
                val source = SimulatedSource(reportSupersessionRemovals = random.nextBoolean())
                // No resurrections: a re-put of a removed id inside the fence window is fenced by
                // design (a late put racing its own removal looks identical) — the reconciler's job.
                Histories.drive(source, GraphCorpus(seed), random, steps = 250, resurrections = false)
                val graph = InMemoryGraphIndex()
                graph.replay(source.history) {}
                assertSameGraph(freshProjection(source), graph.dump(), "seed $seed")
            }
        }

    @Test
    fun twoLosslessFeedsWithoutResurrectionsAreExactWithoutReconciling() =
        runTest {
            // Order-independence proper: no drops, no remove-then-re-put of one id, supersession
            // removals REPORTED, a fence wider than the replay — so only supersession, duplicates
            // and the fence are at work. (Unreported supersession has a residual only the
            // reconciler sees; see GraphIndex's contract.)
            repeat(200) { seed ->
                val random = Random(seed)
                val source = SimulatedSource(reportSupersessionRemovals = true)
                Histories.drive(source, GraphCorpus(seed), random, steps = 250, resurrections = false)
                val graph = InMemoryGraphIndex(fenceSeconds = 1_000_000, nowSecs = { 0 })
                val (delivered, _) = Histories.deliveries(source.history, random, dropRate = 0.0)
                graph.replay(delivered) {}
                assertSameGraph(freshProjection(source), graph.dump(), "seed $seed")
            }
        }

    @Test
    fun droppedRemovalsAreRepairedFromTheirIds() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(1)
            repeat(50) { source.put(corpus.next()) }
            val graph = InMemoryGraphIndex()
            graph.apply(source.held.values.toList())
            val gone = source.held.keys.take(10)
            gone.forEach { source.remove(it) }

            val report = MirrorReconciler(source, graph).reconcileRemovals(gone + source.held.keys.take(3))
            assertEquals(10, report.extra)
            assertEquals(freshProjection(source), graph.dump())
        }

    @Test
    fun largeWindowsSplitUntilTheyFit() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(2)
            repeat(500) { source.put(corpus.next()) }
            val graph = InMemoryGraphIndex()
            val report = MirrorReconciler(source, graph, maxWindowIds = 25).reconcile(0, Long.MAX_VALUE / 2)
            assertEquals(source.held.size.toLong(), report.missing)
            assertEquals(true, report.windows > 1)
            assertEquals(freshProjection(source), graph.dump())
        }
}
