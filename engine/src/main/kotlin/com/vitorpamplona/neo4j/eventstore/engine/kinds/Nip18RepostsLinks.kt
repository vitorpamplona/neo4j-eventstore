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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.utils.lastNotNullOfOrNull

/** Quartz's `nip18Reposts` classes. */
internal fun KindMappers.Builder.nip18Reposts() {
    on<RepostEvent> { e -> nip18RepostLinks(e.tags) }
    on<GenericRepostEvent> { e -> nip18RepostLinks(e.tags) }
}

/**
 * NIP-18 links of kinds 6 and 16. The reposted event is the LAST `e` (and, for an addressable
 * one, the last `a`), as `BaseRepostEvent.boostedEventId` reads it, and its author the last `p`.
 * Any earlier `e`/`a`/`p` is not part of the repost and is a `MENTION`; `k` is the reposted kind.
 * The reposted event's JSON in the content is the same event as the `e`, not another link. Tags
 * are read without their relay hints (`parseId`, `parseKey`, `parseAddress`): no link uses one.
 */
private fun LinkBuilder.nip18RepostLinks(tags: TagArray) {
    val reposted = tags.lastNotNullOfOrNull(ETag::parseId)
    val repostedAddress = tags.lastNotNullOfOrNull(ATag::parseAddress)
    val author = tags.lastNotNullOfOrNull(PTag::parseKey)

    event(Relation.REPOSTED, reposted, ETag.TAG_NAME)
    address(Relation.REPOSTED, repostedAddress, ATag.TAG_NAME)
    user(Relation.REPOSTED_AUTHOR, author, PTag.TAG_NAME)

    each(tags, ETag::parseId) { if (it != reposted) event(Relation.MENTION, it, ETag.TAG_NAME) }
    each(tags, ATag::parseAddress) { if (it != repostedAddress) address(Relation.MENTION, it, ATag.TAG_NAME) }
    each(tags, PTag::parseKey) { if (it != author) user(Relation.MENTION, it, PTag.TAG_NAME) }
    each(tags, KindTag::parse) { value(Relation.REPOSTED_KIND, ValueType.KIND, it.toString(), KindTag.TAG_NAME) }
}
