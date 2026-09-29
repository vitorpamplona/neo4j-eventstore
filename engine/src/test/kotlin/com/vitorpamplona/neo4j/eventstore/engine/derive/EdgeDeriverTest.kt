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

import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.ALICE
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.BOB
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.CAROL
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.event
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.hex
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeDeriverTest {
    private val deriver = EdgeDeriver()

    private fun GraphDoc.edge(
        type: String,
        key: String,
    ): EdgeDoc? = edges.firstOrNull { it.type == type && it.target.key == key }

    private fun GraphDoc.types() = edges.map { it.type }.toSet()

    @Test
    fun theSpecWorkedExampleProjectsAsDocumented() {
        val root = hex("root")
        val parent = hex("parent")
        val mentioned = hex("mentioned")
        val secretKey = hex("a secret key")
        val content = "hello nostr:${mentioned.hexToByteArray().toNpub()} and oops nostr:${secretKey.hexToByteArray().toNsec()}"
        val doc =
            deriver.derive(
                event(
                    1,
                    ALICE,
                    listOf(
                        listOf("e", root, "wss://a", "root"),
                        listOf("e", parent, "", "reply"),
                        listOf("p", BOB),
                        listOf("t", "nostr"),
                        listOf("x", hex("file")),
                    ),
                    content,
                ),
            )

        assertEquals(listOf("root"), doc.edge("e_1", root)!!.props["roles"])
        assertEquals(listOf("reply"), doc.edge("e_1", parent)!!.props["roles"])
        assertEquals(emptyMap(), doc.edge("p_1", BOB)!!.props, "p on a note implies `mention`: nothing stored")
        assertEquals(NodeKind.TAG, doc.edge("t_1", "t:nostr")!!.target.kind)
        assertEquals(mapOf<String, Any>("via" to "content"), doc.edge("ref_p_1", mentioned)!!.props)
        assertNotNull(doc.edge("by_1", ALICE))
        assertTrue(doc.edges.none { it.target.key.contains(hex("file")) }, "x is not an allowlisted tag node")
        assertTrue(doc.edges.none { it.target.key == secretKey }, "a pasted nsec must never become a node")
        assertNull(doc.slot)
    }

    @Test
    fun aSingleUnmarkedETagIsBothRootAndReply() {
        val parent = hex("parent")
        val doc = deriver.derive(event(1, tags = listOf(listOf("e", parent))))
        assertEquals(listOf("root", "reply"), doc.edge("e_1", parent)!!.props["roles"])
    }

    @Test
    fun positionalThreadsFollowNip10() {
        val (a, b, c) = listOf(hex("a"), hex("b"), hex("c"))
        val doc = deriver.derive(event(1, tags = listOf(listOf("e", a), listOf("e", b), listOf("e", c))))
        assertEquals(listOf("root"), doc.edge("e_1", a)!!.props["roles"])
        assertEquals(listOf("mention"), doc.edge("e_1", b)!!.props["roles"])
        assertEquals(listOf("reply"), doc.edge("e_1", c)!!.props["roles"])
    }

    @Test
    fun aFollowListIsAReplaceableSlotOfBareFollowEdges() {
        val doc = deriver.derive(event(3, ALICE, listOf(listOf("p", BOB), listOf("p", CAROL), listOf("p", BOB))))
        assertEquals(Slot.Replaceable(ALICE, 3, "by_3"), doc.slot)
        assertEquals(2, doc.edges.count { it.type == "p_3" }, "duplicate p tags collapse to one edge")
        assertTrue(doc.edges.filter { it.type == "p_3" }.all { it.props.isEmpty() })
    }

    @Test
    fun aReactionMarksItsLastETagAsTheTargetAndKeepsTheSymbol() {
        val context = hex("root of thread")
        val target = hex("the note")
        val doc = deriver.derive(event(7, tags = listOf(listOf("e", context), listOf("e", target), listOf("p", BOB)), content = "🤙"))
        assertEquals(listOf("reaction"), doc.edge("e_7", target)!!.props["roles"])
        assertEquals(listOf("context"), doc.edge("e_7", context)!!.props["roles"])
        assertEquals("🤙", doc.nodeProps["content"])
    }

    @Test
    fun nip22CommentsKeepRootAndReplyApartByCase() {
        val rootEvent = hex("root event")
        val parent = hex("parent comment")
        val doc =
            deriver.derive(
                event(
                    1111,
                    tags =
                        listOf(
                            listOf("E", rootEvent, "", BOB),
                            listOf("K", "1"),
                            listOf("P", BOB),
                            listOf("e", parent, "", CAROL),
                            listOf("k", "1111"),
                            listOf("p", CAROL),
                        ),
                ),
            )
        assertNotNull(doc.edge("E_1111", rootEvent))
        assertNotNull(doc.edge("e_1111", parent))
        assertNotNull(doc.edge("P_1111", BOB))
        assertNotNull(doc.edge("p_1111", CAROL))
        assertNotNull(doc.edge("k_1111", "k:1111"), "k is an allowlisted tag node")
        assertNull(doc.edge("K_1111", "K:1"), "K is not")
    }

    @Test
    fun anArticleIsAnAddressableSlotWithItsTitleAndDTag() {
        val doc =
            deriver.derive(
                event(30023, ALICE, listOf(listOf("d", "my-slug"), listOf("title", "Hello"), listOf("a", "30023:$BOB:other"))),
            )
        val own = "30023:$ALICE:my-slug"
        assertEquals(Slot.Addressable(own), doc.slot)
        assertNotNull(doc.edge("VERSION_OF", own))
        assertNotNull(doc.edge("a_30023", "30023:$BOB:other"))
        assertEquals("my-slug", doc.nodeProps["d"])
        assertEquals("Hello", doc.nodeProps["title"])
        assertTrue(doc.edges.none { it.target.kind == NodeKind.TAG && it.target.key.startsWith("d:") }, "d is covered by the Address")
    }

    @Test
    fun quotesOfAddressesResolveByShape() {
        // Quartz's QTag.parseAddressId rejects every address (it refuses a ':'); the fallback does not.
        val doc = deriver.derive(event(1, tags = listOf(listOf("q", "30023:$BOB:slug"), listOf("q", hex("quoted")))))
        assertEquals(NodeKind.ADDRESS, doc.edge("q_1", "30023:$BOB:slug")!!.target.kind)
        assertEquals(NodeKind.EVENT, doc.edge("q_1", hex("quoted"))!!.target.kind)
    }

    @Test
    fun trustAssertionsPointAtTheirSubjectAndCarryTheScores() {
        val doc = deriver.derive(event(30382, CAROL, listOf(listOf("d", BOB), listOf("rank", "87"), listOf("followers", "1200"))))
        val edge = doc.edge("d_30382", BOB)!!
        assertEquals(NodeKind.USER, edge.target.kind)
        assertEquals(87L, edge.props["rank"])
        assertEquals(1200L, edge.props["followers"])
    }

    @Test
    fun aTrustProviderListLinksItsServices() {
        val doc = deriver.derive(event(10040, ALICE, listOf(listOf("30382:rank", CAROL, "wss://scores.example"))))
        assertEquals("30382:rank", doc.edge("ref_p_10040", CAROL)!!.props["via"])
    }

    @Test
    fun aZapReceiptLinksItsSenderFromTheEmbeddedRequest() {
        val note = hex("zapped note")
        val request = event(9734, CAROL, listOf(listOf("p", BOB), listOf("e", note), listOf("amount", "21000")))
        val receipt = event(9735, hex("lnurl server"), listOf(listOf("p", BOB), listOf("e", note), listOf("description", request.toJson())))
        val doc = deriver.derive(receipt)
        val sender = doc.edge("ref_p_9735", CAROL)!!
        assertEquals("description", sender.props["via"])
        assertEquals(listOf("zapper"), sender.props["roles"])
        assertNotNull(doc.edge("ref_e_9735", request.id))
        assertNotNull(doc.edge("e_9735", note))
        assertNotNull(doc.edge("p_9735", BOB))
        assertEquals(21000L, deriver.derive(request).nodeProps["msats"])
    }

    @Test
    fun anEmbeddedRepostLinksTheOriginalEvenWithoutTags() {
        val original = event(1, BOB, content = "the original")
        val doc = deriver.derive(event(6, ALICE, content = original.toJson()))
        assertEquals("embedded", doc.edge("ref_e_6", original.id)!!.props["via"])
        assertEquals("embedded", doc.edge("ref_p_6", BOB)!!.props["via"])
    }

    @Test
    fun kindsQuartzDoesNotKnowShareTheOtherBucketWithTheirKind() {
        val kind = 12_345
        val doc = deriver.derive(event(kind, ALICE, listOf(listOf("p", BOB), listOf("e", hex("x")))))
        assertEquals(kind.toLong(), doc.edge("p_other", BOB)!!.props["kind"])
        assertEquals(kind.toLong(), doc.edge("by_other", ALICE)!!.props["kind"])
        assertNotNull(doc.edge("e_other", hex("x")))
    }

    @Test
    fun nonCanonicalHexNeverMintsANode() {
        val doc = deriver.derive(event(1, tags = listOf(listOf("p", BOB.uppercase()), listOf("e", "not-hex"))))
        assertEquals(setOf("by_1"), doc.types())
    }

    @Test
    fun aKindZeroCarriesItsCuratedNames() {
        val doc =
            deriver.derive(
                event(0, ALICE, content = """{"name":"alice","display_name":"Alice","nip05":"alice@example.com","about":"x"}"""),
            )
        assertEquals(mapOf("name" to "alice", "display_name" to "Alice", "nip05" to "alice@example.com"), doc.authorProps)
    }

    @Test
    fun reportTypesRideTheReportEdges() {
        val doc = deriver.derive(event(1984, ALICE, listOf(listOf("p", BOB, "spam"), listOf("e", hex("bad"), "illegal"))))
        assertEquals("spam", doc.edge("p_1984", BOB)!!.props["report"])
        assertEquals("illegal", doc.edge("e_1984", hex("bad"))!!.props["report"])
    }

    @Test
    fun anEventNeverLinksToItself() {
        val selfish = event(1, ALICE, content = "no tags")
        val withSelf = event(1, ALICE, listOf(listOf("e", selfish.id)), id = selfish.id)
        assertFalse(deriver.derive(withSelf).edges.any { it.target.key == selfish.id })
    }

    private fun assertNotNull(
        value: Any?,
        message: String? = null,
    ) = kotlin.test.assertNotNull(value, message)
}
