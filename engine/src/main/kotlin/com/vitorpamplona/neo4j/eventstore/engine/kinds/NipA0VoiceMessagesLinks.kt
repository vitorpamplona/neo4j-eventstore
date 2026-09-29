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
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootEventTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootKindTag
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceReplyEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.tags.ReplyAuthorTag
import com.vitorpamplona.quartz.nipA0VoiceMessages.tags.ReplyEventTag
import com.vitorpamplona.quartz.nipA0VoiceMessages.tags.ReplyKindTag
import com.vitorpamplona.quartz.utils.lastNotNullOfOrNull

/** Quartz's `nipA0VoiceMessages` classes. */
internal fun KindMappers.Builder.nipA0VoiceMessages() {
    // NIP-A0 replies follow NIP-22: the root scope (`E`/`A`/`I`, `K`, `P`) and the parent item
    // (`e`/`a`/`i`, `k`, `p`). A reply written before that carries only the parent; Quartz's
    // `rootScopeTags()` recovers its root when the parent is itself a voice message, and those
    // links keep the name of the tag they were read from.
    on<VoiceReplyEvent> { e ->
        if (e.nipA0HasRootScope()) {
            each(e.tags, RootEventTag::parseKey) { event(Relation.ROOT, it, RootEventTag.TAG_NAME) }
            each(e.tags, RootAddressTag::parseAddressId) { address(Relation.ROOT, it, RootAddressTag.TAG_NAME) }
            each(e.tags, RootIdentifierTag.Companion::parse) { tag(Relation.ROOT, RootIdentifierTag.TAG_NAME, it) }
            each(e.tags, RootKindTag::parse) { tag(Relation.TAG, RootKindTag.TAG_NAME, it) }
            each(e.tags, RootAuthorTag::parseKey) { user(Relation.ROOT_AUTHOR, it, RootAuthorTag.TAG_NAME) }
        } else if (e.nipA0RepliesToVoiceMessage()) {
            // An older reply to a voice message: its parent is also its root (see rootScopeTags).
            event(Relation.ROOT, e.replyingTo(), ReplyEventTag.TAG_NAME)
            user(Relation.ROOT_AUTHOR, e.tags.lastNotNullOfOrNull(ReplyAuthorTag::parseKey), ReplyAuthorTag.TAG_NAME)
        }
        each(e.tags, ReplyEventTag::parseKey) { event(Relation.PARENT, it, ReplyEventTag.TAG_NAME) }
        each(e.tags, ReplyAddressTag::parseAddressId) { address(Relation.PARENT, it, ReplyAddressTag.TAG_NAME) }
        each(e.tags, ReplyIdentifierTag::parse) { tag(Relation.PARENT, ReplyIdentifierTag.TAG_NAME, it) }
        each(e.tags, ReplyKindTag::parse) { tag(Relation.TAG, ReplyKindTag.TAG_NAME, it) }
        each(e.tags, ReplyAuthorTag::parseKey) { user(Relation.PARENT_AUTHOR, it, ReplyAuthorTag.TAG_NAME) }
    }

    free<VoiceEvent>()
}

/** Whether the reply names its NIP-22 root scope (`E`, `A` or `I`), not only its parent. Main has no such accessor. */
private fun VoiceReplyEvent.nipA0HasRootScope() =
    tags.any {
        RootEventTag.match(it) || RootAddressTag.match(it) ||
            RootIdentifierTag.match(it)
    }

/** Whether the parent is a voice message (`k` 1222): then a reply without a root scope replies to the root. */
private fun VoiceReplyEvent.nipA0RepliesToVoiceMessage() = tags.any { ReplyKindTag.isKind(it, VoiceEvent.KIND.toString()) }
