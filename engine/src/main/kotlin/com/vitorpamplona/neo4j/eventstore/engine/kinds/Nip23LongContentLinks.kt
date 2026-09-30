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
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent

/** Quartz's `nip23LongContent` classes. */
internal fun KindMappers.Builder.nip23LongContent() {
    // NIP-23: "references to other notes, articles or profiles must be made according to NIP-27
    // ... optionally adding tags for these", so the `e`/`a`/`p` tags are mentions, like the
    // `nostr:` URIs in the text. An article has no thread: though it extends `BaseThreadedEvent`,
    // its `e`/`a` are never a root or a parent. A NIP-73 `i` is what the article is `ABOUT`.
    on<LongFormContentEvent> { e ->
        each(e.tags, ETag::parseId) { event(Relation.MENTION, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parseAddress) { address(Relation.MENTION, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parseKey) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        hashtags(e.tags)
        nip73ExternalIds(e.tags)

        contentMentions(e.content)
    }
}
