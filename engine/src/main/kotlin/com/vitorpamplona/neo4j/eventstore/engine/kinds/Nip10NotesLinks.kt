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
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent

/** Quartz's `nip10Notes` classes. */
internal fun KindMappers.Builder.nip10Notes() {
    // NIP-10, read the way Amethyst threads a note (`root`, `replyingTo`, `threadRootIdOrSelf`):
    // - `ROOT` is the `root`-marked `e`, else the first positional one; a lone `reply` marker is
    //   a direct reply, so its event is the root too.
    // - `PARENT` is the `reply`-marked `e`, else the root (a reply to the root), else the last
    //   positional one. Every other `e` is a `MENTION` (the legacy `mention` marker, or the
    //   positional ones in between), except a `fork`-marked one.
    // - `a` tags follow the same markers (`Nip10MarkedATag`, `nip10MarkedRootAddress`). An `a` to a
    //   NIP-72 community is the `COMMUNITY` the note is posted in, never a thread root; any other
    //   unmarked `a` is a `MENTION`.
    // - A `p` is the `PARENT_AUTHOR` only when it is the author the parent tag itself names (the
    //   `e`'s pubkey slot, or an `a`'s coordinate): NIP-10 adds the replied-to author to the `p`s,
    //   but every thread member rides there too, and nothing else tells them apart.
    on<TextNoteEvent> { e ->
        val parentTag = e.markedReply() ?: e.markedRoot() ?: e.unmarkedReply()
        val rootTag = e.root() ?: e.markedReply()
        val rootAddress = e.nip10MarkedRootAddress() ?: e.nip10MarkedReplyAddress().takeIf { rootTag == null }
        val parentAddress = e.nip10MarkedReplyAddress() ?: e.nip10MarkedRootAddress().takeIf { parentTag == null }
        val parentAuthor = parentTag?.author ?: parentAddress?.address?.pubKeyHex

        event(Relation.ROOT, rootTag, MarkedETag.TAG_NAME)
        address(Relation.ROOT, rootAddress?.address, Nip10MarkedATag.TAG_NAME)
        event(Relation.PARENT, parentTag, MarkedETag.TAG_NAME)
        address(Relation.PARENT, parentAddress?.address, Nip10MarkedATag.TAG_NAME)

        each(e.tags, MarkedETag::parseAllThreadTags) {
            when {
                it.marker == MarkedETag.MARKER.FORK -> event(Relation.FORK, it, MarkedETag.TAG_NAME)
                it.eventId != rootTag?.eventId && it.eventId != parentTag?.eventId -> event(Relation.MENTION, it, MarkedETag.TAG_NAME)
            }
        }
        each(e.tags, PTag::parse) { user(if (it.pubKey == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        each(e.tags, Nip10MarkedATag::parse) {
            when {
                it.address.kind == CommunityDefinitionEvent.KIND -> {
                    address(Relation.COMMUNITY, it.address, Nip10MarkedATag.TAG_NAME)
                }

                it.marker == MarkedETag.MARKER.FORK -> {
                    address(Relation.FORK, it.address, Nip10MarkedATag.TAG_NAME)
                }

                it.address != rootAddress?.address && it.address != parentAddress?.address -> {
                    address(
                        Relation.MENTION,
                        it.address,
                        Nip10MarkedATag.TAG_NAME,
                    )
                }
            }
        }
        hashtags(e.tags)
        each(e.tags, ReferenceTag::parse) { tag(Relation.TAG, ReferenceTag.TAG_NAME, it) }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }

        contentMentions(e.citedNIP19())
    }
}
