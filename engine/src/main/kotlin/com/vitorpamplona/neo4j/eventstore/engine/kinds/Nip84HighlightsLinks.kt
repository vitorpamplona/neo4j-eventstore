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
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyIdentifierTag
import com.vitorpamplona.quartz.nip84Highlights.HighlightEvent

/** Quartz's `nip84Highlights` classes. */
internal fun KindMappers.Builder.nip84Highlights() {
    // NIP-84: the source is an `e`/`a` (a nostr event), an `i` (a NIP-73 id) or an `r` ("a URL or
    // text"), each `HIGHLIGHTED`. A `p` names "the original authors" (`HIGHLIGHTED_AUTHOR`, its
    // `role` — author, editor — riding along when written, `Nip84AttributionTag`); in a quote
    // highlight a `p` or `r` (`Nip84MarkedReferenceTag`) with the `mention` marker is named in the
    // comment instead: a `MENTION`, or a plain URL `TAG`.
    on<HighlightEvent> { e ->
        each(e.tags, ATag::parse) { address(Relation.HIGHLIGHTED, it, ATag.TAG_NAME) }
        each(e.tags, ETag::parse) { event(Relation.HIGHLIGHTED, it, ETag.TAG_NAME) }
        each(e.tags, Nip84MarkedReferenceTag::parse) {
            tag(if (it.isMention()) Relation.TAG else Relation.HIGHLIGHTED, Nip84MarkedReferenceTag.TAG_NAME, it.reference)
        }
        each(e.tags, ReplyIdentifierTag::parse) { tag(Relation.HIGHLIGHTED, ReplyIdentifierTag.TAG_NAME, it) }
        each(e.tags, Nip84AttributionTag::parse) {
            if (it.isMention()) {
                user(Relation.MENTION, it.pubKey, Nip84AttributionTag.TAG_NAME)
            } else {
                user(Relation.HIGHLIGHTED_AUTHOR, it.pubKey, Nip84AttributionTag.TAG_NAME, it.linkProps())
            }
        }
        quotes(e.tags)

        contentMentions(e.citedNIP19())
    }
}
