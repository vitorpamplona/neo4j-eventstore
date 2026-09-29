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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip35Torrents.TorrentCommentEvent
import com.vitorpamplona.quartz.nip35Torrents.TorrentEvent

/** Quartz's `nip35Torrents` classes. */
internal fun KindMappers.Builder.nip35Torrents() {
    // NIP-35: `i` holds external catalogue ids (imdb, tmdb, newznab…) and `t` categories. Quartz's
    // builder turns the description's `nostr:` references into `q` and `p` tags and its URLs into `r`.
    on<TorrentEvent> { e ->
        each(e.tags, Nip35ExternalIdTag::parse) { tag(Relation.TAG, Nip35ExternalIdTag.TAG_NAME, it) }
        hashtags(e.tags)
        quotes(e.tags)
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        each(e.tags, ReferenceTag::parse) { tag(Relation.TAG, ReferenceTag.TAG_NAME, it) }
    }

    // NIP-35: a comment "works exactly like a kind 1": a NIP-10 thread rooted at the torrent. A `p` is the parent's author when it matches the parent `e` tag's author slot.
    on<TorrentCommentEvent> { e ->
        val parentAuthor = nip35ThreadLinks(e.threadTags())
        each(e.tags, PTag::parseKey) {
            val key = LinkBuilder.normalizedHex(it) ?: return@each
            user(if (key == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, key, PTag.TAG_NAME)
        }
        quotes(e.tags)
        contentMentions(e.citedNIP19())
    }
}

/**
 * NIP-10 threading: marked `e` tags say their role, and a thread with a `root` but no `reply`
 * replies to the root. Unmarked tags are positional: the first is the root, the last the parent,
 * the ones between are mentions. Returns the parent's author when its tag names one.
 */
private fun LinkBuilder.nip35ThreadLinks(thread: List<MarkedETag>): HexKey? {
    if (thread.isEmpty()) return null
    var parent: MarkedETag? = null
    if (thread.any { it.marker == MarkedETag.MARKER.ROOT || it.marker == MarkedETag.MARKER.REPLY }) {
        var root: MarkedETag? = null
        thread.forEach {
            when (it.marker) {
                MarkedETag.MARKER.ROOT -> {
                    event(Relation.ROOT, it, MarkedETag.TAG_NAME)
                    if (root == null) root = it
                }

                MarkedETag.MARKER.REPLY -> {
                    event(Relation.PARENT, it, MarkedETag.TAG_NAME)
                    parent = it
                }

                else -> {
                    event(Relation.MENTION, it, MarkedETag.TAG_NAME)
                }
            }
        }
        if (parent == null) {
            parent = root
            event(Relation.PARENT, root, MarkedETag.TAG_NAME)
        }
    } else {
        thread.forEachIndexed { index, it ->
            if (index == 0) event(Relation.ROOT, it, MarkedETag.TAG_NAME)
            if (index == thread.lastIndex) event(Relation.PARENT, it, MarkedETag.TAG_NAME)
            if (index != 0 && index != thread.lastIndex) event(Relation.MENTION, it, MarkedETag.TAG_NAME)
        }
        parent = thread.last()
    }
    return LinkBuilder.normalizedHex(parent?.author)
}
