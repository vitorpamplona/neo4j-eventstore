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
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Properties every derivation must hold, over random events of every kind Quartz types plus
 * unknown ones — the invariants spec §11 names.
 */
class DerivationInvariantsTest {
    private val deriver = EdgeDeriver()
    private val policy = GraphPolicy.Default
    private val literalType = Regex("^([A-Za-z])_(\\d+|other)$")

    private val keys = (0 until 8).map { hex("user$it") }
    private val ids = (0 until 8).map { hex("event$it") }
    private val values =
        keys + ids + keys.map { "30023:$it:slug" } + keys.map { "10002:$it:" } +
            listOf("bitcoin", "nostr", "", "https://example.com", "ABCDEF", keys[0].uppercase(), "x".repeat(400))
    private val names = listOf("e", "p", "a", "q", "E", "P", "A", "t", "d", "k", "x", "z", "r", "i", "zap", "pinned", "alt")

    private fun randomEvent(
        random: Random,
        kind: Int,
    ): Pair<Event, String> {
        val secret = hex("secret${random.nextInt()}")
        val tags =
            (0 until random.nextInt(0, 12)).map {
                val n = names.random(random)
                val v = if (random.nextInt(20) == 0) secret else values.random(random)
                if (random.nextBoolean()) listOf(n, v) else listOf(n, v, "", listOf("root", "reply", "mention", "junk").random(random))
            }
        val content = if (random.nextBoolean()) "leak nostr:${secret.hexToByteArray().toNsec()}" else "plain"
        return event(kind, keys.random(random), tags, content, createdAt = random.nextLong(1, 2_000_000_000)) to secret
    }

    @Test
    fun invariantsHoldOverRandomEvents() {
        val random = Random(7)
        val kinds = (0..65_535).filter { EventFactory.isKnownKind(it) } + listOf(12_345, 22_222, 7_777)
        repeat(20) { round ->
            for (kind in kinds) {
                val (ev, secret) = randomEvent(random, kind)
                val doc = deriver.derive(ev)
                val context = "kind $kind round $round"

                for (edge in doc.edges) {
                    // No self-edges.
                    assertFalse(edge.target.kind == NodeKind.EVENT && edge.target.key == ev.id, "self edge, $context")
                    // Every node key is canonical for its kind.
                    when (edge.target.kind) {
                        NodeKind.EVENT -> {
                            assertTrue(isCanonicalHex64(edge.target.key), "event key ${edge.target.key}, $context")
                        }

                        NodeKind.USER -> {
                            if (edge.type.startsWith("by_")) Unit else assertTrue(isCanonicalHex64(edge.target.key), "user key, $context")
                        }

                        NodeKind.ADDRESS -> {
                            assertEquals(canonicalAddress(edge.target.key), edge.target.key, context)
                        }

                        NodeKind.TAG -> {
                            val name = edge.target.key.substringBefore(':')
                            assertTrue(name in policy.tagNodeNames, "tag node $name not allowlisted, $context")
                            assertTrue(
                                edge.target.key
                                    .substringAfter(':')
                                    .encodeToByteArray()
                                    .size <= policy.maxTagValueBytes,
                                context,
                            )
                        }
                    }
                    // A secret key revealed by a pasted nsec never becomes a node.
                    // (The event's OWN address is its identity, not a reference — VERSION_OF is exempt.)
                    if (edge.type !=
                        "VERSION_OF"
                    ) {
                        assertFalse(edge.target.key.contains(secret) && ev.content.contains("nsec1"), "leaked secret, $context")
                    }
                    // Every literal edge is backed by a tag of that name.
                    literalType.find(edge.type)?.let { m ->
                        val name = m.groupValues[1]
                        assertTrue(ev.tags.any { it.size >= 2 && it[0] == name }, "literal ${edge.type} without a `$name` tag, $context")
                    }
                }
                // One edge per (type, target).
                assertEquals(
                    doc.edges.size,
                    doc.edges
                        .map { it.type to it.target }
                        .toSet()
                        .size,
                    "duplicate edges, $context",
                )
                // Exactly one authorship edge, to the author.
                assertEquals(listOf(ev.pubKey), doc.edges.filter { it.type.startsWith("by_") }.map { it.target.key }, context)
            }
        }
    }
}
