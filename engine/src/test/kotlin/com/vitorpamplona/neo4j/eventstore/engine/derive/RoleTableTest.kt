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
package com.vitorpamplona.neo4j.eventstore.engine.derive

import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.event
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.hex
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleTableTest {
    private val deriver = EdgeDeriver()

    /**
     * The stored `root` / `reply` roles agree with Quartz's own reading of a note's thread
     * (TextNoteEvent.root() / reply()) over random mixes of marked, unmarked and odd-position
     * markers — so "a reply" in the graph means what it means in Amethyst.
     */
    @Test
    fun rootAndReplyMatchQuartzOnRandomThreads() {
        val random = Random(11)
        val ids = (0 until 6).map { hex("thread$it") }
        repeat(2_000) { round ->
            val tags =
                (0 until random.nextInt(1, 6)).map {
                    val id = ids.random(random)
                    when (random.nextInt(5)) {
                        0 -> listOf("e", id)
                        1 -> listOf("e", id, "wss://r")
                        2 -> listOf("e", id, "", listOf("root", "reply", "mention", "fork").random(random))
                        3 -> listOf("e", id, "wss://r", listOf("root", "reply").random(random), hex("author"))
                        else -> listOf("e", id, "wss://r", hex("author"), listOf("root", "reply").random(random))
                    }
                }
            val plain = event(1, tags = tags, createdAt = round.toLong())
            val note = EdgeDeriver.typed(plain) as TextNoteEvent
            val doc = deriver.derive(plain)

            fun withRole(role: String) =
                doc.edges
                    .filter { it.type == "e_1" && role in (it.props["roles"] as List<*>) }
                    .map { it.target.key }
                    .toSet()

            note.root()?.eventId?.let { assertTrue(it in withRole("root"), "round $round: Quartz root $it not stored as root; tags=$tags") }
            note.reply()?.eventId?.let {
                assertTrue(
                    it in withRole("reply"),
                    "round $round: Quartz reply $it not stored as reply; tags=$tags",
                )
            }
            // Every e edge carries at least one role.
            doc.edges.filter { it.type == "e_1" }.forEach { assertTrue((it.props["roles"] as List<*>).isNotEmpty()) }
        }
    }

    @Test
    fun repostsMarkTheirLastETagAsTheReposted() {
        val (a, b) = hex("context") to hex("reposted")
        val doc = deriver.derive(event(6, tags = listOf(listOf("e", a), listOf("e", b))))
        assertEquals(listOf("repost"), doc.edges.first { it.target.key == b }.props["roles"])
        assertEquals(listOf("context"), doc.edges.first { it.target.key == a }.props["roles"])
    }

    @Test
    fun impliedRolesAreNeverStored() {
        val doc = deriver.derive(event(3, tags = listOf(listOf("p", hex("x")))))
        assertTrue(doc.edges.none { it.props.containsKey("roles") })
    }
}
