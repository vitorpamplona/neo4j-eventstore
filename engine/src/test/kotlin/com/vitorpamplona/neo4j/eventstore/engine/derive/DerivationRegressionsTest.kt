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
import com.vitorpamplona.neo4j.eventstore.engine.schema.LONG_D_PREFIX
import com.vitorpamplona.neo4j.eventstore.engine.schema.canonicalAddress
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes the audit of 2026-09-29 found derivation getting wrong, one test each, end to end
 * through the kind mappers: they held under schema 1.1 and must keep holding.
 */
class DerivationRegressionsTest {
    private val deriver = EdgeDeriver()

    @Test
    fun aCapitalizedNsecNeverBecomesAUser() {
        val secret = hex("secret")
        val nsec = secret.hexToByteArray().toNsec()
        for (cased in listOf("N" + nsec.drop(1), nsec.uppercase())) {
            val doc = deriver.derive(event(1, ALICE, content = "oops nostr:$cased"))
            assertTrue(doc.edges.none { it.target.key.contains(secret) }, "leaked through $cased")
        }
    }

    @Test
    fun aTagValueCarryingAnNsecIsNotATagNode() {
        val nsec = hex("secret").hexToByteArray().toNsec()
        val doc = deriver.derive(event(1, ALICE, listOf(listOf("t", nsec), listOf("r", "https://x.example/?k=$nsec"), listOf("t", "fine"))))
        assertEquals(listOf("t:fine"), doc.edges.filter { it.target.kind == NodeKind.TAG }.map { it.target.key })
    }

    @Test
    fun theReportTypeIsReadAsQuartzReadsIt() {
        val id = hex("reported")
        val doc =
            deriver.derive(
                event(1984, ALICE, listOf(listOf("p", BOB, "wss://relay.example/", "nudity"), listOf("e", id, "", "spam"))),
            )
        assertEquals("nudity", doc.edges.single { it.type == "REPORTED_AUTHOR" }.props["report"])
        assertEquals("spam", doc.edges.single { it.type == "REPORTED" }.props["report"])
    }

    @Test
    fun oneServiceNamedForTwoAssertionsKeepsBothVias() {
        val doc =
            deriver.derive(
                event(
                    10040,
                    ALICE,
                    listOf(listOf("30382:rank", CAROL, "wss://s.example"), listOf("30382:followers", CAROL, "wss://s.example")),
                ),
            )
        assertEquals(
            setOf("30382:rank", "30382:followers"),
            doc.edges
                .filter { it.target.key == CAROL }
                .map { it.props["via"] }
                .toSet(),
        )
    }

    @Test
    fun aLongDJoinsTheGraphByItsHashAndStillMatchesItsReferences() {
        val longD = "x".repeat(10_000)
        val own = deriver.derive(event(30023, BOB, listOf(listOf("d", longD))))
        val address = own.slot!!.address
        assertTrue(address.length < 200, "bounded key: ${address.length}")
        assertTrue(address.startsWith("30023:$BOB:$LONG_D_PREFIX"))
        val citing = deriver.derive(event(1, ALICE, listOf(listOf("a", "30023:$BOB:$longD"))))
        assertEquals(
            address,
            citing.edges
                .single { it.target.kind == NodeKind.ADDRESS }
                .target.key,
        )
    }

    @Test
    fun outOfRangeKindsAreNotAddresses() {
        assertNull(canonicalAddress("99999:$BOB:x"))
        assertNull(canonicalAddress("-1:$BOB:x"))
        assertEquals("30023:$BOB:a:b", canonicalAddress("30023:$BOB:a:b"))
        assertEquals("3:$BOB:", canonicalAddress("03:$BOB:"))
    }

    @Test
    fun aHugeZapAmountIsDroppedNotWrapped() {
        val doc = deriver.derive(event(9734, ALICE, listOf(listOf("p", BOB), listOf("amount", Long.MAX_VALUE.toString()))))
        assertNull(doc.nodeProps["msats"])
        val ok = deriver.derive(event(9734, ALICE, listOf(listOf("p", BOB), listOf("amount", "21000"))))
        assertEquals(21000L, ok.nodeProps["msats"])
    }

    @Test
    fun aTrailingMalformedETagDoesNotStealTheReactionRole() {
        val target = hex("liked")
        val doc = deriver.derive(event(7, ALICE, listOf(listOf("e", target), listOf("e", "")), content = "+"))
        assertEquals(listOf(target), doc.edges.filter { it.type == "REACTED" }.map { it.target.key })
    }

    @Test
    fun onlyTheFirstDOfAnAssertionIsItsSubject() {
        val doc = deriver.derive(event(30382, ALICE, listOf(listOf("d", BOB), listOf("d", CAROL), listOf("rank", "90"))))
        assertEquals(listOf(BOB), doc.edges.filter { it.type == "SUBJECT" }.map { it.target.key })
    }

    @Test
    fun aReportAboutAUserIsSplitFromOneAboutTheirContent() {
        // Schema 1.1's `scope` property is the relation now: filtering on a property reads every
        // edge, a relation per meaning is a constant-time count.
        val userWide = deriver.derive(event(1984, ALICE, listOf(listOf("p", BOB, "impersonation"))))
        assertEquals(listOf("REPORTED_USER"), userWide.edges.filter { it.target.key == BOB }.map { it.type })

        for (about in listOf(
            listOf("e", hex("note"), "spam"),
            listOf("a", "30023:$BOB:post", "spam"),
            listOf("x", "ab".repeat(32), "malware"),
        )) {
            val doc = deriver.derive(event(1984, ALICE, listOf(about, listOf("p", BOB))))
            assertEquals(listOf("REPORTED_AUTHOR"), doc.edges.filter { it.target.key == BOB }.map { it.type }, "about ${about[0]}")
        }
        val note = deriver.derive(event(1984, ALICE, listOf(listOf("e", hex("note"), "spam"), listOf("p", BOB))))
        assertEquals("spam", note.edges.single { it.type == "REPORTED_AUTHOR" }.props["report"], "the author inherits the report's type")
    }

    @Test
    fun anInventedReportTypeKeepsItsTextBesideItsCategory() {
        val doc = deriver.derive(event(1984, ALICE, listOf(listOf("e", hex("note"), "", " Swearing "), listOf("p", BOB))))
        val e = doc.edges.single { it.type == "REPORTED" }.props
        assertEquals("other", e["report"])
        assertEquals("swearing", e["report_raw"])
        assertEquals(
            "swearing",
            doc.edges.single { it.type == "REPORTED_AUTHOR" }.props["report_raw"],
            "the author edge carries the report's type text",
        )

        val localized = deriver.derive(event(1984, ALICE, listOf(listOf("p", BOB, "Spam \uD83D\uDCE3"))))
        val p = localized.edges.single { it.type == "REPORTED_USER" }.props
        assertEquals("spam", p["report"], "a localized label folds into its category")
        assertEquals("spam \uD83D\uDCE3", p["report_raw"])

        val untyped = deriver.derive(event(1984, ALICE, listOf(listOf("p", BOB))))
        assertEquals(null, untyped.edges.single { it.type == "REPORTED_USER" }.props["report_raw"], "no text when none was written")
    }
}
