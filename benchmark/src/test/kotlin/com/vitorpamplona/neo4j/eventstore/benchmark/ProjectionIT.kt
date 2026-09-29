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
package com.vitorpamplona.neo4j.eventstore.benchmark

import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.Neo4jGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.reconcile.MirrorReconciler
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.SIG
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.hex
import com.vitorpamplona.neo4j.eventstore.sim.Histories
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Neo4j binding against the executable spec (spec §11): the SAME deliveries — two
 * reordered, lossy writer feeds, reported and unreported supersession, resurrections — replayed
 * into [Neo4jGraphIndex] and [InMemoryGraphIndex] must leave identical graphs, both right after
 * the replay (every intermediate rule agrees, not just the end state) and after one reconcile
 * (where both must also equal a fresh projection of the source).
 */
@Tag("integration")
class ProjectionIT {
    private fun assertSameGraph(
        expected: GraphDump,
        actual: GraphDump,
        message: String,
    ) {
        if (expected == actual) return
        fail(
            buildString {
                appendLine(message)
                (expected.nodes - actual.nodes).take(8).forEach { appendLine("  missing node $it") }
                (actual.nodes - expected.nodes).take(8).forEach { appendLine("  extra node   $it") }
                (expected.edges - actual.edges).take(8).forEach { appendLine("  missing edge $it") }
                (actual.edges - expected.edges).take(8).forEach { appendLine("  extra edge   $it") }
            },
        )
    }

    private suspend fun GraphIndex.replay(changes: List<SimulatedSource.Change>) {
        for (c in changes) {
            when (c) {
                is SimulatedSource.Change.Put -> apply(listOf(c.event))
                is SimulatedSource.Change.Remove -> unapply(listOf(c.id))
            }
        }
    }

    @Test
    fun neo4jMatchesTheExecutableSpecOnRandomHistories() =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            for (seed in 0 until 12) {
                driver.session().use { it.run("MATCH (n) WHERE NOT n:Meta DETACH DELETE n").consume() }
                val random = Random(seed)
                val source = SimulatedSource(reportSupersessionRemovals = random.nextBoolean())
                Histories.drive(source, GraphCorpus(seed), random, steps = 160)
                val (delivered, _) = Histories.deliveries(source.history, random, dropRate = 0.05)

                val clock = { 1_000L }
                val neo4j = Neo4jGraphIndex(driver, nowSecs = clock)
                val spec = InMemoryGraphIndex(nowSecs = clock)
                neo4j.replay(delivered)
                spec.replay(delivered)
                assertSameGraph(spec.dump(), neo4j.dump(), "seed $seed: after replay")

                MirrorReconciler(source, neo4j, maxWindowIds = 30).reconcile(0, Long.MAX_VALUE / 2)
                val fresh = InMemoryGraphIndex().apply { apply(source.held.values.toList(), authoritative = true) }.dump()
                assertSameGraph(fresh, neo4j.dump(), "seed $seed: after reconcile")

                for (id in source.held.keys.take(20)) {
                    assertEquals(
                        InMemoryGraphIndex()
                            .apply {
                                apply(listOf(source.held[id]!!), authoritative = true)
                            }.edgesOf(id),
                        neo4j.edgesOf(id),
                        "seed $seed: edgesOf($id)",
                    )
                }
            }
            driver.close()
        }

    @Test
    fun visitIdsPagesInCreatedAtThenIdOrder() =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            val corpus = GraphCorpus(99)
            val events = (0 until 120).map { corpus.next() }.filter { it.kind == 1 || it.kind == 7 }
            val neo4j = Neo4jGraphIndex(driver)
            neo4j.apply(events)
            val seen = ArrayList<Pair<Long, String>>()
            neo4j.visitIds(0, Long.MAX_VALUE / 2, pageSize = 7) { page ->
                seen += page.map { it.createdAt to it.id }
                true
            }
            assertEquals(events.map { it.createdAt to it.id }.sortedWith(compareBy({ it.first }, { it.second })), seen)
            driver.close()
        }

    // Shapes the random histories rarely hit: a new version that tags the version it replaces
    // (the incumbent must survive as a stub, not be deleted and re-MERGEd in one transaction),
    // and a removal of an id never held (it fences, and leaves no node behind).
    @Test
    fun supersedingAVersionThatTheNewOneCites(): Unit =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            val author = hex("author")
            val v1 = Event(hex("v1"), author, 100, 30023, arrayOf(arrayOf("d", "doc")), "", SIG)
            val v2 = Event(hex("v2"), author, 200, 30023, arrayOf(arrayOf("d", "doc"), arrayOf("e", v1.id)), "", SIG)
            val l1 = Event(hex("l1"), author, 100, 3, arrayOf(arrayOf("p", hex("friend"))), "", SIG)
            val l2 = Event(hex("l2"), author, 200, 3, arrayOf(arrayOf("p", hex("friend")), arrayOf("e", l1.id)), "", SIG)

            val clock = { 1_000L }
            val neo4j = Neo4jGraphIndex(driver, nowSecs = clock)
            val spec = InMemoryGraphIndex(nowSecs = clock)
            for (index in listOf(neo4j, spec)) {
                index.apply(listOf(v1, l1))
                index.apply(listOf(v2, l2))
                index.unapply(listOf(hex("never-held")))
            }
            val dump = neo4j.dump()
            assertSameGraph(spec.dump(), dump, "after superseding")
            assertTrue(dump.nodes.any { it.key == v1.id && !it.stored }, "the cited old version stays as a stub")
            assertTrue(dump.nodes.none { it.key == hex("never-held") }, "a fenced removal leaves no node")
            assertEquals(1, neo4j.apply(listOf(Event(hex("never-held"), author, 5, 1, emptyArray(), "", SIG))).fenced)
            driver.close()
        }
}
