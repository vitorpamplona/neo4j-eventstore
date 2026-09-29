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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelCreateEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelHideMessageEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelMetadataEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelMuteUserEvent
import com.vitorpamplona.quartz.nip28PublicChat.list.PublicChatListEvent
import com.vitorpamplona.quartz.nip28PublicChat.list.tags.ChannelTag
import com.vitorpamplona.quartz.nip28PublicChat.message.ChannelMessageEvent

/** Quartz's `nip28PublicChat` classes. */
internal fun KindMappers.Builder.nip28PublicChat() {
    // NIP-28 gives kind 40 no tags (its metadata and relays live in the content JSON); the `a`
    // tags Quartz reads as address hints are mentions, since nothing gives them another meaning.
    on<ChannelCreateEvent> { e -> each(e.tags, ATag::parse) { address(Relation.MENTION, it, ATag.TAG_NAME) } }

    // The channel (`channel()`, the `root`-marked `e`) is the `ROOT`; every other `e` is `HIDDEN`.
    on<ChannelHideMessageEvent> { e ->
        event(Relation.ROOT, e.channel(), MarkedETag.TAG_NAME)
        e.eventsToHide().forEach { event(Relation.HIDDEN, it, ETag.TAG_NAME) }
    }

    // NIP-28 tags the channel a metadata update is for with the `root` marker: its `ROOT`.
    on<ChannelMetadataEvent> { e -> event(Relation.ROOT, e.channel(), MarkedETag.TAG_NAME) }

    // The `p`s are `CHANNEL_MUTED`: channel moderation, not the author's personal mute list. The
    // channel is the `ROOT` (Quartz adds it; NIP-28's own 44 carries only the `p`).
    on<ChannelMuteUserEvent> { e ->
        event(Relation.ROOT, e.channel(), MarkedETag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.CHANNEL_MUTED, it, PTag.TAG_NAME) }
    }

    // NIP-51: the public chats (kind 40 channels) the user follows are `SUBSCRIBED`.
    on<PublicChatListEvent> { e -> each(e.tags, ChannelTag::parse) { event(Relation.SUBSCRIBED, it.eventId, ChannelTag.TAG_NAME) } }

    // NIP-28: the channel is the `root`-marked `e`, so it is the `ROOT` (`channel()`); the replied-to
    // message is the `reply`-marked one, the `PARENT` (`reply()`, never the channel itself). NIP-28
    // adds the replied-to author as a `p`: it is the `PARENT_AUTHOR` when the parent tag names that
    // same author (Quartz writes it in the `e`'s pubkey slot), else a `MENTION`, as on kind 1.
    on<ChannelMessageEvent> { e ->
        val channelTag = e.channel()
        val parentTag = e.reply()?.takeIf { it.eventId != channelTag?.eventId }
        val parentAuthor = parentTag?.author

        event(Relation.ROOT, channelTag, MarkedETag.TAG_NAME)
        event(Relation.PARENT, parentTag, MarkedETag.TAG_NAME)

        each(e.tags, ETag::parse) {
            if (it.eventId != channelTag?.eventId && it.eventId != parentTag?.eventId) event(Relation.MENTION, it, ETag.TAG_NAME)
        }
        each(e.tags, PTag::parse) { user(if (it.pubKey == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        each(e.tags, ATag::parse) { address(Relation.MENTION, it, ATag.TAG_NAME) }

        contentMentions(e.citedNIP19())
    }
}
