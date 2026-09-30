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
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.Derivation
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.reconcile.MirrorReconciler
import com.vitorpamplona.neo4j.eventstore.reconcile.SourceOfTruth
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
                val fresh = InMemoryGraphIndex().apply { apply(source.held.values.toList()) }.dump()
                assertSameGraph(fresh, neo4j.dump(), "seed $seed: after reconcile")

                for (id in source.held.keys.take(20)) {
                    assertEquals(
                        InMemoryGraphIndex()
                            .apply {
                                apply(listOf(source.held[id]!!))
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

    // A graph an older derivation wrote (here: an older tag-value bound, so fewer tags linked)
    // is re-derived IN PLACE by the reconciler, which reads the stale stamp off each node.
    @Test
    fun aStaleStampedGraphIsReDerivedByTheReconciler(): Unit =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            val source = SimulatedSource()
            val corpus = GraphCorpus(41)
            repeat(150) { source.put(corpus.next()) }
            val oldPolicy = GraphPolicy(maxTagValueBytes = 4)
            Neo4jGraphIndex(driver, deriver = EdgeDeriver(oldPolicy)).apply(source.held.values.toList())
            val fresh = InMemoryGraphIndex().apply { apply(source.held.values.toList()) }.dump()

            val neo4j = Neo4jGraphIndex(driver)
            val stamps = HashSet<Long>()
            neo4j.visitIds(Long.MIN_VALUE, Long.MAX_VALUE) { page ->
                page.forEach { stamps += it.derived }
                true
            }
            assertEquals(setOf(Derivation.stamp(oldPolicy)), stamps, "the older build's stamp")
            assertTrue(neo4j.dump() != fresh, "the older derivation projects differently")

            val report = MirrorReconciler(source, neo4j).reconcile(Long.MIN_VALUE, Long.MAX_VALUE)
            assertEquals(source.held.size.toLong(), report.rederived)
            assertSameGraph(fresh, neo4j.dump(), "after re-deriving")
            assertEquals(0L, MirrorReconciler(source, neo4j).reconcile(Long.MIN_VALUE, Long.MAX_VALUE).rederived)
            driver.close()
        }

    /** A source whose fetch answers, then lets [afterFetch] change the world before the caller acts on it. */
    private class RacingSource(
        val inner: SimulatedSource,
        var afterFetch: suspend () -> Unit = {},
    ) : SourceOfTruth by inner {
        override suspend fun fetch(ids: List<String>): List<Event> =
            inner.fetch(ids).also {
                val hook = afterFetch
                afterFetch = {}
                hook()
            }
    }

    // The two authoritative-apply races, against Neo4j and the spec alike: a removal the feed
    // makes after the reconciler's fetch must not be undone, and a newer version the feed
    // applies after it must not be evicted by the older one the fetch returned.
    @Test
    fun aReconcileApplyLosesToWhatTheFeedDidAfterTheFetch(): Unit =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            val author = hex("author")
            val note = Event(hex("deleted"), author, 1_700_000_010, 1, emptyArray(), "bye", SIG)
            val v1 = Event(hex("v1"), author, 1_700_000_020, 30023, arrayOf(arrayOf("d", "doc")), "", SIG)
            val v2 = Event(hex("v2"), author, 1_700_000_030, 30023, arrayOf(arrayOf("d", "doc")), "", SIG)
            var now = 1_800_000_000L
            val neo4j = Neo4jGraphIndex(driver, nowSecs = { now })
            val spec = InMemoryGraphIndex(nowSecs = { now })
            for (index in listOf<GraphIndex>(neo4j, spec)) {
                val source = RacingSource(SimulatedSource().apply { put(note) })
                source.afterFetch = {
                    source.inner.remove(note.id)
                    index.unapply(listOf(note.id))
                }
                MirrorReconciler(source, index, nowSecs = { now }).reconcile(1_700_000_000, 1_700_000_015)

                source.inner.put(v1)
                source.afterFetch = {
                    source.inner.put(v2)
                    index.apply(listOf(v2))
                }
                MirrorReconciler(source, index, nowSecs = { now }).reconcile(1_700_000_016, 1_700_000_025)
                now += 1
            }
            val dump = neo4j.dump()
            assertSameGraph(spec.dump(), dump, "Neo4j and the spec agree")
            assertEquals(
                setOf(v2.id),
                dump.nodes
                    .filter { it.stored }
                    .map { it.key }
                    .toSet(),
            )
            driver.close()
        }

    // Rare shapes the random histories do not reach, compared with the spec: a new version of a
    // list whose old version's address was the only reference to its owner (the owner must be
    // KEPT, since the new version's address re-MERGEs it in the same transaction), and a profile
    // signed with an uppercase pubkey (names on the AUTHOR edge's user, no second user).
    @Test
    fun supersedingKeepsTheOwnerOfTheNewVersionsAddressesAndNamesFollowTheAuthorEdge(): Unit =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            val me = hex("me")
            val bob = hex("bob")
            val l1 = Event(hex("l1"), me, 100, 10003, arrayOf(arrayOf("a", "30023:$bob:old")), "", SIG)
            val l2 = Event(hex("l2"), me, 200, 10003, arrayOf(arrayOf("a", "30023:$bob:new")), "", SIG)
            val upper = hex("upper")
            val profile = Event(hex("profile"), upper.uppercase(), 100, 0, emptyArray(), "{\"name\":\"up\"}", SIG)

            val clock = { 1_000L }
            val neo4j = Neo4jGraphIndex(driver, nowSecs = clock)
            val spec = InMemoryGraphIndex(nowSecs = clock)
            for (index in listOf<GraphIndex>(neo4j, spec)) {
                index.apply(listOf(l1))
                index.apply(listOf(l2))
                index.apply(listOf(profile))
            }
            val dump = neo4j.dump()
            assertSameGraph(spec.dump(), dump, "after superseding")
            assertTrue(dump.nodes.any { it.label == Labels.USER && it.key == bob }, "the new address's owner")
            assertEquals("up", dump.nodes.single { it.label == Labels.USER && it.key == upper }.props["name"])
            assertTrue(dump.nodes.none { it.key == upper.uppercase() })
            driver.close()
        }
}
