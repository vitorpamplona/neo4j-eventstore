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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.NoProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapSplitProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QAddressableTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
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
fun LinkBuilder.hashtags(tags: TagArray) = each(tags, HashtagTag::parse) { tag(Relation.HASHTAG, HashtagTag.TAG_NAME, it.lowercase()) }

/** NIP-18 `q` tags ([QTag]): an event or an address, as [Relation.QUOTE] (or [relation]). */
fun LinkBuilder.quotes(
    tags: TagArray,
    relation: Relation<NoProps> = Relation.QUOTE,
) = each(tags, QTag::parse) {
    when (it) {
        is QEventTag -> event(relation, it.eventId, QTag.TAG_NAME)
        is QAddressableTag -> address(relation, it.address, QTag.TAG_NAME)
    }
}

/**
 * NIP-27 `nostr:` URIs in [content], as [Relation.MENTION]s (or [relation]) `via` content. An
 * nsec is never a link: its hex is a private key.
 */
fun LinkBuilder.contentMentions(
    content: String,
    relation: Relation<NoProps> = Relation.MENTION,
) {
    if (!content.contains("nostr:")) return
    contentMentions(runCatching { Nip19Parser.parseAll(content) }.getOrDefault(emptyList()), relation)
}

/** [contentMentions] from entities a class already parsed (and cached) out of its content. */
fun LinkBuilder.contentMentions(
    entities: List<Entity>,
    relation: Relation<NoProps> = Relation.MENTION,
) = entities.forEach { entity ->
    when (entity) {
        is NPub -> user(relation, entity.hex, Link.VIA_CONTENT)
        is NProfile -> user(relation, entity.hex, Link.VIA_CONTENT)
        is NNote -> event(relation, entity.hex, Link.VIA_CONTENT)
        is NEvent -> event(relation, entity.hex, Link.VIA_CONTENT)
        is NAddress -> address(relation, entity.aTag(), Link.VIA_CONTENT)
        is NEmbed -> event(relation, entity.event.id, Link.VIA_CONTENT)
        else -> Unit
    }
}
