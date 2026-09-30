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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deriver's own rules: links become edges (relation name = type, props + via = properties),
 * the slot, the nsec rule, the `:Tag` bound, curated node values. What each kind's references
 * MEAN is pinned by the mapper golden tests (`kinds/<Package>LinksTest`), not here.
 */
class EdgeDeriverTest {
    private val deriver = EdgeDeriver()

    private fun GraphDoc.edge(
        type: String,
        key: String,
    ): EdgeDoc? = edges.firstOrNull { it.type == type && it.target.key == key }

    @Test
    fun theRelationNameIsTheTypeAndViaIsAProperty() {
        val doc = deriver.derive(event(3, ALICE, listOf(listOf("p", BOB), listOf("p", CAROL), listOf("p", BOB))))
        assertEquals(mapOf<String, Any>("via" to "p"), doc.edge("FOLLOW", BOB)!!.props)
        assertEquals(2, doc.edges.count { it.type == "FOLLOW" }, "duplicate p tags collapse to one edge")
        assertEquals(emptyMap(), doc.edge("AUTHOR", ALICE)!!.props, "no via: nobody wrote the author in a tag")
    }

    @Test
    fun aReplaceableEventCompetesForItsKindPubkeyAddress() {
        val doc = deriver.derive(event(3, ALICE, listOf(listOf("p", BOB))))
        val own = "3:$ALICE:"
        assertEquals(Slot(own), doc.slot)
        assertNotNull(doc.edge("ADDRESS", own))
        assertNull(doc.nodeProps["d"], "a replaceable kind has no d")
    }

    @Test
    fun anAddressableEventCompetesForItsAddressAndKeepsItsDAndTitle() {
        val doc =
            deriver.derive(
                event(30023, ALICE, listOf(listOf("d", "my-slug"), listOf("title", "Hello"), listOf("a", "30023:$BOB:other"))),
            )
        val own = "30023:$ALICE:my-slug"
        assertEquals(Slot(own), doc.slot)
        assertNotNull(doc.edge("ADDRESS", own))
        assertNotNull(doc.edge("MENTION", "30023:$BOB:other"))
        assertEquals("my-slug", doc.nodeProps["d"])
        assertEquals("Hello", doc.nodeProps["title"])
    }

    @Test
    fun aRegularEventHasNoSlot() {
        val doc = deriver.derive(event(1, ALICE, listOf(listOf("d", "not-an-address"))))
        assertNull(doc.slot)
        assertTrue(doc.edges.none { it.type == "ADDRESS" })
    }

    @Test
    fun typedPropsBecomeEdgePropertiesWithNumbersWidened() {
        val report = deriver.derive(event(1984, ALICE, listOf(listOf("p", BOB, "impersonation"))))
        assertEquals(
            mapOf<String, Any>("report" to "impersonation", "report_raw" to "impersonation", "via" to "p"),
            report.edge("REPORTED_USER", BOB)!!.props,
        )
        // NIP-85 scores are Ints in the props class and Longs in the store, as Neo4j returns them.
        val card = deriver.derive(event(30382, CAROL, listOf(listOf("d", BOB), listOf("rank", "87"), listOf("followers", "1200"))))
        val subject = card.edge("SUBJECT", BOB)!!
        assertEquals(87L, subject.props["rank"])
        assertEquals(1200L, subject.props["followers"])
        assertEquals("d", subject.props["via"])
    }

    @Test
    fun aPastedNsecNeverBecomesANodeOrAProperty() {
        val mentioned = hex("mentioned")
        val secretKey = hex("a secret key")
        val nsec = secretKey.hexToByteArray().toNsec()
        val content = "hello nostr:${mentioned.hexToByteArray().toNpub()} and oops nostr:$nsec"
        val doc = deriver.derive(event(1, ALICE, listOf(listOf("p", secretKey), listOf("t", nsec), listOf("t", "nostr")), content))
        assertNotNull(doc.edge("MENTION", mentioned), "the npub is a mention")
        assertTrue(doc.edges.none { it.target.key.contains(secretKey) }, "the secret key's hex is never a node")
        assertTrue(doc.edges.none { it.target.key.contains("nsec1") }, "a bech32 private key is never a tag value")
        assertNotNull(doc.edge("HASHTAG", "hashtag:nostr"))
    }

    @Test
    fun anOverlongTagValueNeverKeysANode() {
        val long = "x".repeat(300)
        val doc = deriver.derive(event(1, ALICE, listOf(listOf("t", long), listOf("t", "ok"))))
        assertNotNull(doc.edge("HASHTAG", "hashtag:ok"))
        assertTrue(doc.edges.none { it.target.key.contains(long) })
    }

    @Test
    fun anEventNeverLinksToItself() {
        val selfish = event(1, ALICE, content = "no tags")
        val withSelf = event(1, ALICE, listOf(listOf("e", selfish.id)), id = selfish.id)
        assertFalse(deriver.derive(withSelf).edges.any { it.target.key == selfish.id })
    }

    @Test
    fun aKindQuartzDoesNotKnowStatesOnlyTheCommonLinks() {
        // No fallback: a tag on an unknown kind means nothing the projection can vouch for.
        val doc = deriver.derive(event(7_777, ALICE, listOf(listOf("p", BOB), listOf("e", hex("x")))))
        assertEquals(listOf("AUTHOR"), doc.edges.map { it.type })
    }

    @Test
    fun uppercaseHexJoinsTheSameNode() {
        val doc = deriver.derive(event(3, ALICE, listOf(listOf("p", BOB.uppercase()))))
        assertNotNull(doc.edge("FOLLOW", BOB))
    }

    @Test
    fun curatedValuesLiveOnTheNodes() {
        val profile =
            deriver.derive(
                event(0, ALICE, content = """{"name":"alice","display_name":"Alice","nip05":"alice@example.com","about":"x"}"""),
            )
        assertEquals(
            mapOf("name" to "alice", "display_name" to "Alice", "nip05" to "alice@example.com"),
            profile.nodeProps.filterKeys { it in Extractors.PROFILE_FIELDS },
            "a profile's names stay on the profile",
        )
        assertEquals("🤙", deriver.derive(event(7, tags = listOf(listOf("e", hex("note"))), content = "🤙")).nodeProps["content"])
        assertEquals(21000L, deriver.derive(event(9734, CAROL, listOf(listOf("p", BOB), listOf("amount", "21000")))).nodeProps["msats"])
    }
}
