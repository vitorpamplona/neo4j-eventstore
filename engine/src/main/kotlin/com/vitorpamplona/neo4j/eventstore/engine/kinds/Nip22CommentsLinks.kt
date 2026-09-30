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
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyKindTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootEventTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootKindTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent

/** Quartz's `nip22Comments` classes. */
internal fun KindMappers.Builder.nip22Comments() {
    // NIP-22: the uppercase tags are the root scope (`E`/`A`/`I` → `ROOT`, `P` → `ROOT_AUTHOR`),
    // the lowercase ones the parent item (`e`/`a`/`i` → `PARENT`). An external-identifier scope
    // (`I`/`i`: a URL, a hashtag, a geohash) is the NIP-73 id it names, one node whichever case
    // named it. A lowercase `p` is the `PARENT_AUTHOR` only when it is the author the parent tag
    // itself names (the `e`'s pubkey slot, `Nip22ParentETag`, or an `a`'s coordinate): NIP-22 also
    // asks for a `p` per pubkey mentioned in the content, and those are `MENTION`s. An `A` root at
    // a NIP-72 community is also the `COMMUNITY` the comment is posted in.
    //
    // The `a`/`A` values are read with `parseAddress`, which decodes an `naddr` the way every `a`
    // parser does, in the scope and in the parent's author alike (main's `ReplyAddressTag.parse`
    // demands a 64-char value and so rejects every real address). Nothing is read with its relay
    // hint: no link uses one, and normalizing each is a lock on Quartz's global relay-url cache.
    on<CommentEvent> { e ->
        // An external id is one node whichever case named it: the target is always an `i`.
        each(e.tags, RootEventTag::parseKey) { event(Relation.ROOT, it, RootEventTag.TAG_NAME) }
        each(e.tags, RootAddressTag::parseAddress) {
            address(Relation.ROOT, it, RootAddressTag.TAG_NAME)
            if (it.kind == CommunityDefinitionEvent.KIND) address(Relation.COMMUNITY, it, RootAddressTag.TAG_NAME)
        }
        each(e.tags, RootIdentifierTag.Companion::parse) { tag(Relation.ROOT, ReplyIdentifierTag.TAG_NAME, it, RootIdentifierTag.TAG_NAME) }
        each(e.tags, RootKindTag::parse) { tag(Relation.TAG, ReplyKindTag.TAG_NAME, it, RootKindTag.TAG_NAME) }
        each(e.tags, RootAuthorTag::parseKey) { user(Relation.ROOT_AUTHOR, it, RootAuthorTag.TAG_NAME) }

        // The authors the parent tags name, gathered as the parents are linked.
        val parentAuthors = HashSet<String>()
        each(e.tags, Nip22ParentETag::parse) {
            event(Relation.PARENT, it.eventId, Nip22ParentETag.TAG_NAME)
            it.author?.let(parentAuthors::add)
        }
        each(e.tags, ReplyAddressTag::parseAddress) {
            address(Relation.PARENT, it, ReplyAddressTag.TAG_NAME)
            parentAuthors.add(it.pubKeyHex)
        }
        each(e.tags, ReplyIdentifierTag::parse) { tag(Relation.PARENT, ReplyIdentifierTag.TAG_NAME, it) }
        each(e.tags, ReplyKindTag::parse) { tag(Relation.TAG, ReplyKindTag.TAG_NAME, it) }

        each(e.tags, ReplyAuthorTag::parseKey) {
            user(if (it in parentAuthors) Relation.PARENT_AUTHOR else Relation.MENTION, it, ReplyAuthorTag.TAG_NAME)
        }

        quotes(e.tags)
        hashtags(e.tags)

        contentMentions(e.content)
    }
}
