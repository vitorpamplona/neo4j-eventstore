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
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QAddressableTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent

/** Quartz's `nipC7Chats` classes. */
internal fun KindMappers.Builder.nipC7Chats() {
    // NIP-C7: a reply quotes its parent in a `q` tag whose fourth slot is the parent's author, so the
    // last `q` is the parent and any other a NIP-18 quote. A chat inside a NIP-29 group names it in `h`.
    on<ChatEvent> { e ->
        val quotes = e.tags.mapNotNull(QTag::parse)
        val parent = quotes.lastOrNull()
        quotes.forEach {
            val relation = if (it === parent) Relation.PARENT else Relation.QUOTE
            when (it) {
                is QEventTag -> event(relation, it.eventId, QTag.TAG_NAME)
                is QAddressableTag -> address(relation, it.address, QTag.TAG_NAME)
            }
        }
        val parentAuthor =
            when (parent) {
                is QEventTag -> parent.author
                is QAddressableTag -> parent.address.pubKeyHex
                else -> null
            }
        user(Relation.PARENT_AUTHOR, parentAuthor, QTag.TAG_NAME)
        // A client that also `p`-tags the parent's author (as kinds 1 and 42 do) is not mentioning
        // them: the `q` already made them the PARENT_AUTHOR, so that `p` adds nothing.
        val parentAuthorKey = LinkBuilder.normalizedHex(parentAuthor)
        each(e.tags, PTag::parse) { if (LinkBuilder.normalizedHex(it.pubKey) != parentAuthorKey) user(Relation.MENTION, it, PTag.TAG_NAME) }
        each(e.tags, GroupIdTag::parse) { tag(Relation.GROUP, GroupIdTag.TAG_NAME, it) }
        contentMentions(e.content)
    }
}
