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

/** Quartz's `nip10Notes` classes. */
internal fun KindMappers.Builder.nip10Notes() {
    // NIP-10, read the way Amethyst threads a note (`root`, `replyingTo`, `threadRootIdOrSelf`),
    // in one pass over the `e` and `a` tags (`nip10Thread`):
    // - `ROOT` is the `root`-marked `e` / `a`; a lone `reply` marker is a direct reply, so its
    //   target is the root too. Without any marker, the first positional `e` is the root.
    // - `PARENT` is the `reply`-marked `e` / `a`, else the root (a reply to the root); without any
    //   marker, the last positional `e`. Every other `e` is a `MENTION` (the legacy `mention`
    //   marker, the positional ones in between, an unmarked one beside markers), except a
    //   `fork`-marked one.
    // - An `a` to a NIP-72 community is the `COMMUNITY` the note is posted in, never a thread
    //   root; any other `a` that is neither root nor parent is a `MENTION`.
    // - A `p` is the `PARENT_AUTHOR` only when it is the author the parent tag itself names (the
    //   `e`'s pubkey slot, or an `a`'s coordinate): NIP-10 adds the replied-to author to the `p`s,
    //   but every thread member rides there too, and nothing else tells them apart.
    // - Mentions in the text are its NIP-27 `nostr:` references (`contentMentions`).
    //
    // Every tag is read without its relay hint (`Nip10ETag`, `PTag::parseKey`, …): no link uses
    // one, and normalizing them was most of a note's mapping cost.
    on<TextNoteEvent> { e ->
        val thread = e.tags.nip10Thread()
        val parentAuthor = thread.parentAuthor

        event(Relation.ROOT, thread.root?.eventId, Nip10ETag.TAG_NAME)
        address(Relation.ROOT, thread.rootAddress, Nip10MarkedATag.TAG_NAME)
        event(Relation.PARENT, thread.parent?.eventId, Nip10ETag.TAG_NAME)
        address(Relation.PARENT, thread.parentAddress, Nip10MarkedATag.TAG_NAME)

        thread.eTags.forEach {
            when {
                it.marker == MarkedETag.MARKER.FORK -> {
                    event(Relation.FORK, it.eventId, Nip10ETag.TAG_NAME)
                }

                it.eventId != thread.root?.eventId && it.eventId != thread.parent?.eventId -> {
                    event(Relation.MENTION, it.eventId, Nip10ETag.TAG_NAME)
                }
            }
        }
        each(e.tags, PTag::parseKey) { user(if (it == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        thread.aTags.forEach {
            when {
                it.isCommunity -> {
                    address(Relation.COMMUNITY, it.address, Nip10MarkedATag.TAG_NAME)
                }

                it.marker == MarkedETag.MARKER.FORK -> {
                    address(Relation.FORK, it.address, Nip10MarkedATag.TAG_NAME)
                }

                it.address != thread.rootAddress && it.address != thread.parentAddress -> {
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

        contentMentions(e.content)
    }
}
