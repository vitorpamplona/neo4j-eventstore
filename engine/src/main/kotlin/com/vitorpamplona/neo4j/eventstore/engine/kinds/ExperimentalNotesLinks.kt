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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RatingProps
import com.vitorpamplona.quartz.experimental.forks.parseFork
import com.vitorpamplona.quartz.experimental.nipsOnNostr.NipTextEvent
import com.vitorpamplona.quartz.experimental.ratings.EntityRatingEvent
import com.vitorpamplona.quartz.experimental.ratings.RatingMark
import com.vitorpamplona.quartz.experimental.ratings.RelayReviewEvent
import com.vitorpamplona.quartz.experimental.zapPolls.ZapPollEvent
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyKindTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAddressTag

/** Quartz's `experimental` note-like classes: NIP texts, ratings and zap polls. */
internal fun KindMappers.Builder.experimentalNotes() {
    // The version this text forks (an `a` or `e` marked `fork`), quotes, mentions (unmarked `a`,
    // `p`, NIP-27 URIs in the text) and the kinds it defines (`k`).
    on<NipTextEvent> { e ->
        each(e.tags, ExperimentalForkATag::parseForked) { address(Relation.FORK, it, ATag.TAG_NAME) }
        each(e.tags, MarkedETag::parseFork) { event(Relation.FORK, it, MarkedETag.TAG_NAME) }
        each(e.tags, ExperimentalForkATag::parseUnforked) { address(Relation.MENTION, it, ATag.TAG_NAME) }
        quotes(e.tags)
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
        contentMentions(e.content)
    }

    on<EntityRatingEvent> { e -> experimentalRatingLinks(e) }
    free<RelayReviewEvent>()

    on<ZapPollEvent> { e -> experimentalZapPollLinks(e) }
}

/**
 * The rated entity and its author, each carrying the rating's `mark` and `stars` (0..5, see
 * `stars`).
 *
 * The spec's own slot for the entity is `d`: like a NIP-85 assertion's subject it names what
 * the event is about, not the event itself, so it is a link, typed by the mark (a profile is
 * a user, a relay is not modelled, an id an event, a coordinate an address, and anything else,
 * a hashtag or a book, the `d` value as written, mark prefix included, which is what keeps it
 * unique). The clients' extension tags (`a`/`A`, `e`, `p`, `k`) repeat the target; one link
 * is emitted per distinct target.
 */
private fun LinkBuilder.experimentalRatingLinks(e: EntityRatingEvent) {
    val mark = e.mark()
    val props = RatingProps(mark, e.stars())
    // The targets already linked, so the `d` below does not repeat one of them.
    val rated = HashSet<String>()
    each(e.tags, ATag::parse) { if (rated.add(it.toTag())) address(Relation.RATED, it, ATag.TAG_NAME, props) }
    each(e.tags, RootAddressTag::parseAddressId) { if (rated.add(it)) address(Relation.RATED, it, RootAddressTag.TAG_NAME, props) }
    each(e.tags, ETag::parse) { if (rated.add(it.eventId)) event(Relation.RATED, it, ETag.TAG_NAME, props) }
    // NIP-73 kinds as written: a book's `k` is `isbn`, not a number.
    each(e.tags, ReplyKindTag::parse) { tag(Relation.TAG, ReplyKindTag.TAG_NAME, it) }
    each(e.tags, PTag::parse) { user(Relation.RATED_AUTHOR, it, PTag.TAG_NAME, props) }
    val target = e.targetIdentifier()
    if (target.isNotEmpty() && target !in rated) {
        when {
            mark == RatingMark.PROFILE -> user(Relation.RATED, target, DTag.TAG_NAME, props)
            mark == RatingMark.RELAY -> Unit
            target.length == 64 -> event(Relation.RATED, target, DTag.TAG_NAME, props)
            LinkBuilder.normalizedAddress(target) != null -> address(Relation.RATED, target, DTag.TAG_NAME, props)
            else -> tag(Relation.RATED, DTag.TAG_NAME, e.dTag(), DTag.TAG_NAME, props)
        }
    }
}

/**
 * Kind 1's reading (NIP-10): a zap poll is a threaded note plus `poll_option` tags. Marked
 * `e` tags name the root and the parent (a lone `root` is also the parent); without markers
 * the deprecated positional scheme applies (first `e` the root, last the parent, the ones
 * between mentions). A `p` is the parent's author when it matches the author slot of the
 * parent's `e`, else a mention. Votes are zaps, not links of the poll.
 */
private fun LinkBuilder.experimentalZapPollLinks(e: ZapPollEvent) {
    // Every `e`, malformed ids included: each one still takes its place in the positional
    // scheme, and the builder drops the ones that are not event ids.
    val thread = e.tags.mapNotNull(MarkedETag::parseAllThreadTags)
    var markedRoot = -1
    var markedReply = -1
    var firstUnmarked = -1
    var lastUnmarked = -1
    thread.forEachIndexed { i, eTag ->
        when (eTag.marker) {
            MarkedETag.MARKER.ROOT -> {
                if (markedRoot < 0) markedRoot = i
            }

            MarkedETag.MARKER.REPLY -> {
                markedReply = i
            }

            null -> {
                if (firstUnmarked < 0) firstUnmarked = i
                lastUnmarked = i
            }

            else -> {
                Unit
            }
        }
    }
    val marked = markedRoot >= 0 || markedReply >= 0
    val root = if (marked) markedRoot else firstUnmarked
    val parent =
        when {
            !marked -> lastUnmarked
            markedReply >= 0 -> markedReply
            else -> markedRoot
        }
    val parentAuthor = thread.getOrNull(parent)?.author

    thread.forEachIndexed { i, eTag ->
        if (i == root) event(Relation.ROOT, eTag, MarkedETag.TAG_NAME)
        if (i == parent) event(Relation.PARENT, eTag, MarkedETag.TAG_NAME)
        if (i != root && i != parent) {
            event(if (eTag.marker == MarkedETag.MARKER.FORK) Relation.FORK else Relation.MENTION, eTag, MarkedETag.TAG_NAME)
        }
    }
    each(e.tags, PTag::parse) { user(if (it.pubKey == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, it, PTag.TAG_NAME) }
    quotes(e.tags)
    each(e.tags, ATag::parse) { address(Relation.MENTION, it, ATag.TAG_NAME) }
    contentMentions(e.content)
}
