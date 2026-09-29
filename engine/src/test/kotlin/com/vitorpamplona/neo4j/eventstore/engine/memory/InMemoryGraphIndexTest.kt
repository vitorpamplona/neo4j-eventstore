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
package com.vitorpamplona.neo4j.eventstore.engine.memory

import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.ALICE
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.BOB
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.CAROL
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.event
import com.vitorpamplona.neo4j.eventstore.engine.GraphDump
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryGraphIndexTest {
    private var now = 1_000_000L
    private val graph = InMemoryGraphIndex(fenceSeconds = 3_600, nowSecs = { now })

    private fun GraphDump.node(
        label: String,
        key: String,
    ) = nodes.firstOrNull { it.label == label && it.key == key }

    @Test
    fun aReplyBeforeItsParentLeavesAStubThatTheParentFills() =
        runTest {
            val parent = event(1, BOB, content = "parent")
            val reply = event(1, ALICE, listOf(listOf("e", parent.id)), "reply")

            graph.apply(listOf(reply))
            assertEquals(false, graph.dump().node(Labels.EVENT, parent.id)!!.stored, "stub first")

            graph.apply(listOf(parent))
            assertEquals(true, graph.dump().node(Labels.EVENT, parent.id)!!.stored, "stub promoted")

            graph.unapply(listOf(parent.id))
            assertEquals(false, graph.dump().node(Labels.EVENT, parent.id)!!.stored, "still referenced: back to a stub")

            graph.unapply(listOf(reply.id))
            assertEquals(GraphDump(emptySet(), emptySet()), graph.dump(), "nothing references anything: empty")
        }

    @Test
    fun aNewerFollowListReplacesTheOlderAndAStaleOneIsIgnored() =
        runTest {
            val v1 = event(3, ALICE, listOf(listOf("p", BOB)), createdAt = 100)
            val v2 = event(3, ALICE, listOf(listOf("p", CAROL)), createdAt = 200)
            graph.apply(listOf(v1, v2))
            assertNull(graph.edgesOf(v1.id))
            assertEquals(listOf(CAROL), graph.edgesOf(v2.id)!!.filter { it.type == "p_3" }.map { it.targetKey })
            assertNull(graph.dump().node(Labels.USER, BOB), "Bob was only followed by the superseded list")

            val outcome = graph.apply(listOf(v1))
            assertEquals(1, outcome.stale)
            assertNull(graph.edgesOf(v1.id))
        }

    @Test
    fun theNip01TiebreakKeepsTheLowerIdOnEqualTimestamps() =
        runTest {
            val a = event(3, ALICE, listOf(listOf("p", BOB)), createdAt = 100)
            val b = event(3, ALICE, listOf(listOf("p", CAROL)), createdAt = 100)
            val (low, high) = if (a.id < b.id) a to b else b to a
            graph.apply(listOf(high, low))
            assertTrue(graph.edgesOf(low.id) != null && graph.edgesOf(high.id) == null)
            graph.apply(listOf(high))
            assertTrue(graph.edgesOf(low.id) != null && graph.edgesOf(high.id) == null, "order does not matter")
        }

    @Test
    fun anAddressableVersionMovesVersionOfAndKeepsTheAddressWhileReferenced() =
        runTest {
            val v1 = event(30023, BOB, listOf(listOf("d", "post"), listOf("title", "One")), createdAt = 100)
            val v2 = event(30023, BOB, listOf(listOf("d", "post"), listOf("title", "Two")), createdAt = 200)
            val quote = event(1, ALICE, listOf(listOf("a", "30023:$BOB:post")))
            graph.apply(listOf(v1, quote, v2))
            val address = "30023:$BOB:post"
            val versions = graph.dump().edges.filter { it.type == "VERSION_OF" && it.toKey == address }
            assertEquals(listOf(v2.id), versions.map { it.fromKey })

            graph.unapply(listOf(v2.id))
            val dump = graph.dump()
            assertNotNull(dump.node(Labels.ADDRESS, address), "the quote still points at the address")
            assertTrue(dump.edges.any { it.type == "OWNED_BY" && it.fromKey == address && it.toKey == BOB })
        }

    @Test
    fun aLatePutIsFencedButTheReconcilerCanForceItAndTheFenceExpires() =
        runTest {
            val note = event(1, ALICE, content = "hi")
            graph.unapply(listOf(note.id))
            assertEquals(1, graph.apply(listOf(note)).fenced)
            assertEquals(1, graph.apply(listOf(note), authoritative = true).applied)

            graph.unapply(listOf(note.id))
            now += 3_601
            assertEquals(1, graph.apply(listOf(note)).applied, "fence window passed")
        }

    @Test
    fun kindZeroNamesComeAndGoWithTheCurrentProfile() =
        runTest {
            val v1 = event(0, ALICE, content = """{"name":"alice"}""", createdAt = 100)
            val v2 = event(0, ALICE, content = """{"nip05":"a@b.c"}""", createdAt = 200)
            graph.apply(listOf(v1))
            assertEquals(mapOf<String, Any>("name" to "alice"), graph.dump().node(Labels.USER, ALICE)!!.props)
            graph.apply(listOf(v2))
            assertEquals(mapOf<String, Any>("nip05" to "a@b.c"), graph.dump().node(Labels.USER, ALICE)!!.props, "no stale name left behind")
            graph.unapply(listOf(v2.id))
            assertNull(graph.dump().node(Labels.USER, ALICE))
        }

    @Test
    fun visitIdsIsAscendingByTimeThenId() =
        runTest {
            val events = (1..20).map { event(1, ALICE, content = "n$it", createdAt = (it % 4).toLong()) }
            graph.apply(events)
            val seen = mutableListOf<Pair<Long, String>>()
            graph.visitIds(0, 10, pageSize = 3) { page ->
                seen += page.map { it.createdAt to it.id }
                true
            }
            assertEquals(events.map { it.createdAt to it.id }.sortedWith(compareBy({ it.first }, { it.second })), seen)
        }

    @Test
    fun tagNodesAreSharedAndDisappearWithTheirLastUser() =
        runTest {
            val a = event(1, ALICE, listOf(listOf("t", "nostr")), "a")
            val b = event(1, BOB, listOf(listOf("t", "nostr")), "b")
            graph.apply(listOf(a, b))
            assertEquals(1, graph.dump().nodes.count { it.label == Labels.TAG })
            graph.unapply(listOf(a.id))
            assertEquals(1, graph.dump().nodes.count { it.label == Labels.TAG })
            graph.unapply(listOf(b.id))
            assertEquals(0, graph.dump().nodes.count { it.label == Labels.TAG })
        }
}
