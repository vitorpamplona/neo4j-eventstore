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
import com.vitorpamplona.neo4j.eventstore.engine.schema.canonicalAddress
import com.vitorpamplona.neo4j.eventstore.engine.schema.isCanonicalHex64
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
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
    private val relations = Relation.ALL.mapTo(HashSet()) { it.name }

    private val keys = (0 until 8).map { hex("user$it") }
    private val ids = (0 until 8).map { hex("event$it") }
    private val values =
        keys + ids + keys.map { "30023:$it:slug" } + keys.map { "10002:$it:" } +
            listOf("bitcoin", "nostr", "", "https://example.com", "ABCDEF", keys[0].uppercase(), "x".repeat(400))
    private val names =
        listOf(
            "e",
            "p",
            "a",
            "q",
            "E",
            "P",
            "A",
            "I",
            "K",
            "t",
            "d",
            "k",
            "x",
            "z",
            "r",
            "i",
            "h",
            "g",
            "l",
            "L",
            "zap",
            "client",
            "emoji",
            "pinned",
            "alt",
        )

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
                    // Every type is a relation of the vocabulary.
                    assertTrue(edge.type in relations, "unknown relation ${edge.type}, $context")
                    // Every node key is canonical for its kind.
                    when (edge.target.kind) {
                        NodeKind.EVENT -> {
                            assertTrue(isCanonicalHex64(edge.target.key), "event key ${edge.target.key}, $context")
                        }

                        NodeKind.USER -> {
                            if (edge.type != "AUTHOR") assertTrue(isCanonicalHex64(edge.target.key), "user key, $context")
                        }

                        NodeKind.ADDRESS -> {
                            assertEquals(canonicalAddress(edge.target.key), edge.target.key, context)
                        }

                        NodeKind.TAG -> {
                            assertTrue(
                                edge.target.key
                                    .substringBefore(':')
                                    .isNotEmpty(),
                                context,
                            )
                            assertTrue(policy.fitsTagNode(edge.target.key.substringAfter(':')), context)
                        }
                    }
                    // A secret key revealed by a pasted nsec never becomes a node or a property.
                    // (The event's OWN address is its identity, not a reference: ADDRESS is exempt.)
                    if (edge.type != "ADDRESS" && ev.content.contains("nsec1")) {
                        assertFalse(edge.target.key.contains(secret), "leaked secret, $context")
                        assertFalse(edge.props.values.any { it.toString().contains(secret) }, "leaked secret in props, $context")
                    }
                    // Values are only the shapes Neo4j hands back.
                    edge.props.values.forEach { v ->
                        assertTrue(v is String || v is Long || v is Double || v is Boolean || v is List<*>, "prop ${v::class}, $context")
                    }
                }
                // Exactly one authorship edge, to the author.
                assertEquals(listOf(ev.pubKey), doc.edges.filter { it.type == "AUTHOR" }.map { it.target.key }, context)
                // The slot is the event's own ADDRESS edge, and only replaceable / addressable kinds have one.
                assertEquals(doc.edges.filter { it.type == "ADDRESS" }.map { it.target.key }, listOfNotNull(doc.slot?.address), context)
            }
        }
    }
}
