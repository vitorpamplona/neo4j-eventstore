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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip37Drafts.DraftWrapEvent
import com.vitorpamplona.quartz.nip37Drafts.privateOutbox.PrivateOutboxRelayListEvent

/** Quartz's `nip37Drafts` classes. */
internal fun KindMappers.Builder.nip37Drafts() {
    /*
     * NIP-37: the draft itself is encrypted. Quartz copies its thread anchors into public tags
     * (`ExposeInDraft`) so a draft shows in context: the channel or live activity it belongs to
     * (ROOT) and the message it replies to (PARENT). `k` says which kind the draft is.
     */
    on<DraftWrapEvent> { e ->
        each(e.tags, KindTag::parse) { value(Relation.DRAFT_KIND, ValueType.KIND, it.toString(), KindTag.TAG_NAME) }
        each(e.tags, MarkedETag::parseRoot) { event(Relation.ROOT, it, MarkedETag.TAG_NAME) }
        each(e.tags, MarkedETag::parseReply) { event(Relation.PARENT, it, MarkedETag.TAG_NAME) }
        each(e.tags, ATag::parse) { address(Relation.ROOT, it, ATag.TAG_NAME) }
    }
    free<PrivateOutboxRelayListEvent>()
}
