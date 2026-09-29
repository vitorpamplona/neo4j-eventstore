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
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The derivation's core promise, checked against EVERY class Quartz types: an id a hint provider
 * names is never lost — it lands on some edge (literal or derived). This is the property the
 * per-row golden tests of docs/appendix-providers.md sample; here it is exhaustive, so a Quartz
 * bump that adds a provider is covered the day it lands.
 */
class ProviderCoverageTest {
    private val deriver = EdgeDeriver()

    // Every reference shape the providers read: plain, NIP-22 uppercase, quotes, parent lists,
    // and multi-letter tags some classes use; plus content links.
    private val eid = hex("e-target")
    private val quoted = hex("q-target")
    private val root = hex("E-target")
    private val parentList = hex("z-target")
    private val pinned = hex("pinned-target")
    private val contentEvent = hex("content-event")
    private val contentUser = hex("content-user")
    private val tags =
        listOf(
            listOf("e", eid, "", "reply"),
            listOf("E", root, "", BOB),
            listOf("p", BOB),
            listOf("P", CAROL),
            listOf("a", "30023:$BOB:article"),
            listOf("A", "30023:$CAROL:root-article"),
            listOf("q", quoted),
            listOf("z", parentList),
            listOf("pinned", pinned),
            listOf("exercise", "33401:$BOB:squat"),
            listOf("template", "33402:$BOB:leg-day"),
            listOf("d", "slug"),
        )
    private val content =
        "see nostr:${contentUser.hexToByteArray().toNpub()} and nostr:${NEvent.create(contentEvent, BOB, 1, null)}"

    @Test
    fun everyProviderLinkLandsOnAnEdge() {
        var checkedClasses = 0
        val failures = mutableListOf<String>()
        for (kind in 0..65_535) {
            if (!EventFactory.isKnownKind(kind)) continue
            val plain = event(kind, ALICE, tags, content)
            val typed = EdgeDeriver.typed(plain)
            if (typed !is EventHintProvider && typed !is PubKeyHintProvider && typed !is AddressHintProvider) continue
            checkedClasses++

            val expected = HashSet<String>()
            runCatching { (typed as? EventHintProvider)?.linkedEventIds() }
                .getOrNull()
                ?.filter {
                    isCanonicalHex64(
                        it,
                    ) && it != plain.id
                }?.let {
                    expected +=
                        it
                }
            runCatching { (typed as? PubKeyHintProvider)?.linkedPubKeys() }.getOrNull()?.filter { isCanonicalHex64(it) }?.let {
                expected +=
                    it
            }
            runCatching { (typed as? AddressHintProvider)?.linkedAddressIds() }.getOrNull()?.mapNotNull { canonicalAddress(it) }?.let {
                expected +=
                    it
            }

            val targets = deriver.derive(plain).edges.mapTo(HashSet()) { it.target.key }
            val lost = expected - targets
            if (lost.isNotEmpty()) failures += "${typed::class.simpleName} ($kind) lost $lost"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(checkedClasses >= 100, "expected ~109 provider classes, checked $checkedClasses")
    }
}
