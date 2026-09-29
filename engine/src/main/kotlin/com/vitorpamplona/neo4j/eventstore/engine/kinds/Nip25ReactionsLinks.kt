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
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyKindTag
import com.vitorpamplona.quartz.nip25Reactions.ExternalReactionEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip25Reactions.tags.ExternalTargetTag
import com.vitorpamplona.quartz.utils.lastNotNullOfOrNull

/** Quartz's `nip25Reactions` classes. */
internal fun KindMappers.Builder.nip25Reactions() {
    // NIP-25: "the target event id should be last of the `e` tags" and "the target event pubkey
    // should be last of the `p` tags", so the LAST `e`/`a` is what was `REACTED` to and the last
    // `p` its `REACTED_AUTHOR` (not `originalPost`/`originalAuthor`, which return all of them).
    // Earlier ones are copies of the target's thread tags: `MENTION`s. `k` is the target's kind.
    on<ReactionEvent> { e ->
        val reacted = e.tags.lastNotNullOfOrNull(ETag::parse)
        val reactedAddress = e.tags.lastNotNullOfOrNull(ATag::parse)
        val author = e.tags.lastNotNullOfOrNull(PTag::parse)

        event(Relation.REACTED, reacted, ETag.TAG_NAME)
        address(Relation.REACTED, reactedAddress, ATag.TAG_NAME)
        user(Relation.REACTED_AUTHOR, author, PTag.TAG_NAME)

        each(e.tags, ETag::parse) { if (it.eventId != reacted?.eventId) event(Relation.MENTION, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parse) { if (it.toTag() != reactedAddress?.toTag()) address(Relation.MENTION, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parse) { if (it.pubKey != author?.pubKey) user(Relation.MENTION, it, PTag.TAG_NAME) }
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
    }

    // NIP-25 kind 17: every NIP-73 `i` is a `REACTED` external id (a podcast reaction names both
    // the show and the episode), `k` their kinds. The `i`'s URL hint is not a target.
    on<ExternalReactionEvent> { e ->
        each(e.tags, ExternalTargetTag::parse) { tag(Relation.REACTED, ExternalTargetTag.TAG_NAME, it) }
        each(e.tags, ReplyKindTag::parse) { tag(Relation.TAG, ReplyKindTag.TAG_NAME, it) }
    }
}
