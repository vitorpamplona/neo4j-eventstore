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
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent

/** Quartz's `nip17Dm` classes. */
internal fun KindMappers.Builder.nip17Dm() {
    // NIP-17: `p` are the receivers, `e` "the direct parent message this post is replying to", `q` a NIP-18 quote.
    on<ChatMessageEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.RECIPIENT, it, PTag.TAG_NAME) }
        each(e.tags, ETag::parse) { event(Relation.PARENT, it, ETag.TAG_NAME) }
        quotes(e.tags)
        contentMentions(e.content)
    }
    // A file message's `x` and `file-type` describe its blob: not links.
    on<ChatMessageEncryptedFileHeaderEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.RECIPIENT, it, PTag.TAG_NAME) }
        each(e.tags, ETag::parse) { event(Relation.PARENT, it, ETag.TAG_NAME) }
    }
    free<DmRelayListEvent>()
}
