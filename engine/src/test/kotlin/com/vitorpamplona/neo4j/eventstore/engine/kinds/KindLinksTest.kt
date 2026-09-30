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
package com.vitorpamplona.neo4j.eventstore.engine.kinds

import com.vitorpamplona.neo4j.eventstore.engine.vocab.Link
import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkTarget
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.links
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapSplitProps
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.utils.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KindLinksTest {
    private val pk = "1".repeat(64)
    private val id = "2".repeat(64)

    @Test
    fun malformedTargetsAreDroppedAndHexIsLowercased() {
        val built =
            links {
                event(Relation.PARENT, "not-an-id", "e")
                event(Relation.PARENT, "g".repeat(64), "e")
                user(Relation.MENTION, "A".repeat(64), "p")
                address(Relation.QUOTE, "30023:short:d", "q")
                address(Relation.QUOTE, "30023:${"B".repeat(64)}:post", "q")
                address(Relation.QUOTE, "10006:$pk:", "q")
                value(Relation.HASHTAG, ValueType.HASHTAG, " ", "t")
                event(Relation.PARENT, id, "e")
                event(Relation.PARENT, id, "e")
            }
        assertEquals(
            listOf(
                Link(Relation.MENTION, LinkTarget.User("a".repeat(64)), "p"),
                Link(Relation.QUOTE, LinkTarget.Address("30023:${"b".repeat(64)}:post"), "q"),
                Link(Relation.QUOTE, LinkTarget.Address("10006:$pk:"), "q"),
                Link(Relation.PARENT, LinkTarget.Event(id), "e"),
            ),
            built,
        )
    }

    @Test
    fun contentMentionsNeverLinkAnNsec() {
        val npub = Hex.decode(pk).toNpub()
        val nsec = Hex.decode("3".repeat(64)).toNsec()
        val nevent = NEvent.create(id, null, null, null)
        val naddr = NAddress.create(30023, pk, "post", null)
        val built = links { contentMentions("hi nostr:$npub and nostr:$nsec see nostr:$nevent nostr:$naddr") }
        assertEquals(
            listOf(
                Link(Relation.MENTION, LinkTarget.User(pk), Link.VIA_CONTENT),
                Link(Relation.MENTION, LinkTarget.Event(id), Link.VIA_CONTENT),
                Link(Relation.MENTION, LinkTarget.Address("30023:$pk:post"), Link.VIA_CONTENT),
            ),
            built,
        )
    }

    @Test
    fun everyEventStatesItsAuthorAndTheEveryKindTags() {
        val handler = "31990:$pk:app"
        val set = "30030:$pk:blobs"
        val event =
            NostrSignerSync().sign<Event>(
                1L,
                12345,
                arrayOf(
                    arrayOf("client", "Amethyst", handler, "wss://relay.example/"),
                    arrayOf("zap", pk, "wss://relay.example/", "3"),
                    arrayOf("emoji", "blob", "https://img.example/blob.png", set),
                    arrayOf("emoji", "plain", "https://img.example/plain.png"),
                ),
                "",
            )
        assertEquals(
            listOf(
                Link(Relation.AUTHOR, LinkTarget.User(event.pubKey)),
                // 12345 is a replaceable kind: its slot is `kind:pubkey:`.
                Link(Relation.ADDRESS, LinkTarget.Address("12345:${event.pubKey}:")),
                Link(Relation.CLIENT, LinkTarget.Address(handler), "client"),
                Link(Relation.ZAP_SPLIT, LinkTarget.User(pk), "zap", ZapSplitProps(weight = 3.0)),
                Link(Relation.EMOJI_SET, LinkTarget.Address(set), "emoji"),
            ),
            KindLinks.of(event),
        )
    }

    @Test
    fun anExternalIdIsKeyedByWhatItNames() {
        val built =
            links {
                external(Relation.ROOT, "https://example.com/post", "I")
                external(Relation.ROOT, "#Nostr", "I")
                external(Relation.ROOT, "geo:U4PRUY", "I")
                external(Relation.ROOT, "isbn:9780765382030", "I")
                external(Relation.ROOT, "#", "I")
            }
        assertEquals(
            listOf(
                Link(Relation.ROOT, LinkTarget.Tag(ValueType.URL, "https://example.com/post"), "I"),
                Link(Relation.ROOT, LinkTarget.Tag(ValueType.HASHTAG, "nostr"), "I"),
                Link(Relation.ROOT, LinkTarget.Tag(ValueType.GEOHASH, "u4pruy"), "I"),
                Link(Relation.ROOT, LinkTarget.Tag(ValueType.EXTERNAL, "isbn:9780765382030"), "I"),
            ),
            built,
        )
    }

    @Test
    fun aValueTakesOneFormPerType() {
        assertEquals(ValueType.URL.normalize("https://example.com/a"), ValueType.URL.normalize("HTTPS://Example.COM/a#x"))
        assertEquals("spotify:search:x y", ValueType.URL.normalize("spotify:search:x y"), "another scheme is kept as written")
        assertEquals("u4pruy", ValueType.GEOHASH.normalize("U4PRUY"))
        assertEquals("nostr", ValueType.HASHTAG.normalize("Nostr"))
        assertEquals("isbn:9780765382030", ValueType.EXTERNAL.normalize("isbn:9780765382030"))
        assertEquals(
            listOf(Link(Relation.LOCATION, LinkTarget.Tag(ValueType.GEOHASH, "u4pruy"), "g")),
            links { value(Relation.LOCATION, ValueType.GEOHASH, "U4PRUY", "g") },
        )
    }

    @Test
    fun aRelationTakesOnlyTheTargetsItDeclares() {
        assertFailsWith<IllegalArgumentException> { links { user(Relation.HASHTAG, pk, "p") } }
        assertFailsWith<IllegalArgumentException> { links { value(Relation.FOLLOW, ValueType.HASHTAG, "nostr", "t") } }
    }

    @Test
    fun everyRelationDeclaresItsTargets() {
        Relation.ALL.forEach { assertTrue(it.targets.isNotEmpty(), "${it.name} declares no targets") }
    }

    @Test
    fun relationNamesAreUniqueAndUpperSnake() {
        assertEquals(
            Relation.ALL.size,
            Relation.ALL
                .map { it.name }
                .toSet()
                .size,
        )
        Relation.ALL.forEach { assertEquals(it.name.uppercase(), it.name) }
    }
}
