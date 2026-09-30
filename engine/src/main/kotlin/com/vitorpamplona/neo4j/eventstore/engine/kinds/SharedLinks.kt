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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.NoProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapSplitProps
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEmbed
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip30CustomEmoji.EmojiUrlTag
import com.vitorpamplona.quartz.nip57Zaps.splits.BaseZapSplitSetup
import com.vitorpamplona.quartz.nip57Zaps.splits.ZapSplitSetup
import com.vitorpamplona.quartz.nip57Zaps.splits.ZapSplitSetupParser
import com.vitorpamplona.quartz.nip89AppHandlers.clientTag.ClientTag

// Tags many kinds share, read once here so no mapper repeats them.

/**
 * The tags NIP-89, NIP-57 and NIP-30 let ANY event carry: a `client` tag's handler address
 * ([Relation.CLIENT]), a zap split's beneficiary ([Relation.ZAP_SPLIT], with its weight: a split
 * SETTING, not a payment, so not `ZAP_RECIPIENT`; a lightning-address split names no user), and
 * the 30030 set a custom emoji comes from ([Relation.EMOJI_SET]).
 */
fun LinkBuilder.everyKindLinks(tags: TagArray) {
    each(tags, ClientTag::parse) { address(Relation.CLIENT, it.address, ClientTag.TAG_NAME) }
    each(tags, ZapSplitSetupParser::parse) {
        if (it is ZapSplitSetup) user(Relation.ZAP_SPLIT, it.pubKeyHex, BaseZapSplitSetup.TAG_NAME, ZapSplitProps(it.weight))
    }
    each(tags, EmojiUrlTag::parse) { address(Relation.EMOJI_SET, it.emojiSet, EmojiUrlTag.TAG_NAME) }
}

/** NIP-24 `t` tags ([HashtagTag]). Hashtags are case-insensitive, so the value is lowercased: #Nostr is #nostr. */
fun LinkBuilder.hashtags(tags: TagArray) =
    each(tags, HashtagTag::parse) { value(Relation.HASHTAG, ValueType.HASHTAG, it.lowercase(), HashtagTag.TAG_NAME) }

/**
 * NIP-18 `q` tags ([QTag]): an event or an address, as [Relation.QUOTE] (or [relation]). Read the
 * way [QTag.parse] reads them (a 64-char value is an event id, anything else an address, an
 * `naddr` decoded) but without the relay hint it normalizes, which no link uses.
 */
fun LinkBuilder.quotes(
    tags: TagArray,
    relation: Relation<NoProps> = Relation.QUOTE,
) = each(tags, QTag::parseId) {
    if (it.length == HEX_ID_LENGTH) event(relation, it, QTag.TAG_NAME) else address(relation, Address.parse(it), QTag.TAG_NAME)
}

/** A NIP-01 event id or pubkey in hex. */
private const val HEX_ID_LENGTH = 64

/**
 * NIP-27 references in [content], as [Relation.MENTION]s (or [relation]) `via` content: every
 * [nip27Entities] entity but an nsec, whose hex is a private key and is never a link.
 */
fun LinkBuilder.contentMentions(
    content: String,
    relation: Relation<NoProps> = Relation.MENTION,
    via: String = Link.VIA_CONTENT,
) = nip27Entities(content).forEach { entity ->
    when (entity) {
        is NPub -> user(relation, entity.hex, via)
        is NProfile -> user(relation, entity.hex, via)
        is NNote -> event(relation, entity.hex, via)
        is NEvent -> event(relation, entity.hex, via)
        is NAddress -> address(relation, entity.aTag(), via)
        is NEmbed -> event(relation, entity.event.id, via)
        else -> Unit
    }
}

/** NIP-21's URI scheme, lowercase: [nip27Entities] folds the text to it. */
private const val NIP21_SCHEME = "nostr:"

/** The NIP-19 entities a [nip27Entities] reference can name whose bech32 payload is always 58 chars. */
private val NIP27_FIXED_58_PREFIXES = arrayOf("npub1", "note1")

/** The NIP-19 entities a [nip27Entities] reference can name whose payload varies (TLV). */
private val NIP27_VARIABLE_PREFIXES = arrayOf("nprofile1", "nevent1", "naddr1", "nembed1")

/**
 * The NIP-19 entities [content] REFERENCES in the NIP-27 sense: those written as a NIP-21
 * `nostr:` URI, in text order. The one reading of content every mapper shares.
 *
 * Only the `nostr:` form counts. Quartz's `Nip19Parser.parseAll` (and `citedNIP19()`, built on
 * it) also returns bare entities, so an `npub1…` inside a URL (`https://njump.me/npub1…`, a
 * profile link that names someone the author did not tag) would become a `MENTION`. The scheme is
 * matched ASCII-case-insensitively, as URI schemes are (RFC 3986): a phone's auto-capitalization
 * writes `Nostr:npub1…`, and that is still a reference. Only the entities a reference can name are
 * read (no `nsec`, whose hex is a private key, and no `nrelay`), each right after the scheme and
 * decoded by Quartz's own `Nip19Parser.parseComponents`; an invalid one is skipped.
 */
internal fun nip27Entities(content: String): List<Entity> {
    var colon = content.indexOf(':')
    if (colon < 0) return emptyList()
    var found: ArrayList<Entity>? = null
    while (colon >= 0) {
        val start = colon + 1
        var next = colon + 1
        if (nip27SchemeEndsAt(content, colon)) {
            val end = nip27EntityEnd(content, start)
            if (end > start) {
                // The hrp ends at its bech32 separator, the first `1`: no NIP-19 hrp contains one.
                val prefixEnd = content.indexOf('1', start) + 1
                Nip19Parser
                    .parseComponents(content.substring(start, prefixEnd), content.substring(prefixEnd, end), null)
                    ?.entity
                    ?.let { (found ?: ArrayList<Entity>().also { found = it }).add(it) }
                next = end
            }
        }
        colon = content.indexOf(':', next)
    }
    return found ?: emptyList()
}

/** Whether `nostr` (any ASCII case) ends right before the [colon] at that index. */
private fun nip27SchemeEndsAt(
    content: String,
    colon: Int,
): Boolean {
    val schemeStart = colon - (NIP21_SCHEME.length - 1)
    if (schemeStart < 0) return false
    for (k in 0 until NIP21_SCHEME.length - 1) {
        val c = content[schemeStart + k]
        val folded = if (c in 'A'..'Z') c + ('a' - 'A') else c
        if (folded != NIP21_SCHEME[k]) return false
    }
    return true
}

/** The end (exclusive) of a NIP-19 entity starting at [start], or [start] when none starts there. */
private fun nip27EntityEnd(
    content: String,
    start: Int,
): Int {
    for (prefix in NIP27_FIXED_58_PREFIXES) {
        if (!content.regionMatchesAscii(start, prefix)) continue
        val dataStart = start + prefix.length
        val dataEnd = dataStart + NIP19_FIXED_PAYLOAD
        if (dataEnd > content.length) return start
        for (k in dataStart until dataEnd) if (!Bech32.isDataChar(content[k])) return start
        return dataEnd
    }
    for (prefix in NIP27_VARIABLE_PREFIXES) {
        if (!content.regionMatchesAscii(start, prefix)) continue
        val dataStart = start + prefix.length
        var dataEnd = dataStart
        while (dataEnd < content.length && Bech32.isDataChar(content[dataEnd])) dataEnd++
        return if (dataEnd > dataStart) dataEnd else start
    }
    return start
}

/** A 32-byte key or id in bech32: 52 data chars plus the 6-char checksum. */
private const val NIP19_FIXED_PAYLOAD = 58

/** ASCII-case-insensitive [prefix] (lowercase) at [offset]: bech32 may be written all uppercase. */
private fun String.regionMatchesAscii(
    offset: Int,
    prefix: String,
): Boolean {
    if (offset + prefix.length > length) return false
    for (k in prefix.indices) {
        val c = this[offset + k]
        val folded = if (c in 'A'..'Z') c + ('a' - 'A') else c
        if (folded != prefix[k]) return false
    }
    return true
}
