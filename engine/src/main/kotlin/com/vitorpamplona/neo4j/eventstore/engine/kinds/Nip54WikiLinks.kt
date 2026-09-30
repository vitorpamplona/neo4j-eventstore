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
import com.vitorpamplona.quartz.nip54Wiki.WikiArticleEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiMergeAcceptanceEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiMergeRequestEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiRedirectEvent

/** Quartz's `nip54Wiki` classes. */
internal fun KindMappers.Builder.nip54Wiki() {
    // NIP-54 articles have no parent: an `a` or `e` marked `fork` is the version this one was forked
    // from, one marked `defer` a version it considers better than itself, and any other is a citation.
    on<WikiArticleEvent> { e ->
        each(e.tags, Nip54WikiReferenceTag::parse) {
            val relation =
                when (it.marker) {
                    Nip54WikiReferenceTag.FORK_MARKER -> Relation.FORK
                    Nip54WikiReferenceTag.DEFER_MARKER -> Relation.DEFER
                    else -> Relation.MENTION
                }
            when (it) {
                is Nip54WikiReference.EventRef -> event(relation, it.eventId, ETag.TAG_NAME)
                is Nip54WikiReference.AddressRef -> address(relation, it.address, ATag.TAG_NAME)
            }
        }
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        hashtags(e.tags)
        contentMentions(e.content)
    }

    on<WikiMergeAcceptanceEvent> { e ->
        each(e.tags, Nip54WikiReferenceTag::parseEvent) {
            when (it.marker) {
                WikiMergeAcceptanceEvent.RESULT_MARKER -> event(Relation.RESULT, it.eventId, ETag.TAG_NAME)
                WikiMergeAcceptanceEvent.REQUEST_MARKER -> event(Relation.REQUEST, it.eventId, ETag.TAG_NAME)
            }
        }
        each(e.tags, PTag::parse) { user(Relation.REQUEST_AUTHOR, it, PTag.TAG_NAME) }
    }

    // NIP-54: the article to change and its author, the version to merge (`e` marked `source`, or `fork` as clients write it) and the unmarked base version.
    on<WikiMergeRequestEvent> { e ->
        each(e.tags, ATag::parse) { address(Relation.DESTINATION, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(Relation.DESTINATION_AUTHOR, it, PTag.TAG_NAME) }
        each(e.tags, Nip54WikiReferenceTag::parseEvent) {
            when (it.marker) {
                null -> event(Relation.BASE_VERSION, it.eventId, ETag.TAG_NAME)
                in WikiMergeRequestEvent.MERGE_SOURCE_MARKERS -> event(Relation.SOURCE, it.eventId, ETag.TAG_NAME)
            }
        }
    }

    on<WikiRedirectEvent> { e -> each(e.tags, ATag::parse) { address(Relation.REDIRECT, it, ATag.TAG_NAME) } }
}
