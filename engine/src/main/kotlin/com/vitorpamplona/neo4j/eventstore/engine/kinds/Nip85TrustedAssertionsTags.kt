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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ServiceProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.SubjectProps
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.tags.ServiceProviderTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.tags.ServiceType
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.CommentCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.QuoteCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.ReactionCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.RepostCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.ZapAmountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.tags.ZapCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ActiveHoursEndTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ActiveHoursStartTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.FirstCreatedAtTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.FollowerCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.HopsTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.PostCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.RankTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ReactionsCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ReplyCountTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ReportsCountReceivedTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ReportsCountSentTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapAmountReceivedTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapAmountSentTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapAvgAmountDayReceivedTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapAvgAmountDaySentTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapCountReceivedTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.ZapCountSentTag
import com.vitorpamplona.quartz.utils.ensure

/**
 * The `k` of a kind 30385 assertion: the NIP-73 kind of the identifier it scores (`isbn`,
 * `podcast:guid`, `web`, …). A NIP-73 kind is a name, not an event kind number, so main's
 * numeric `KindTag` cannot read it, and main has no parser of its own for it.
 */
internal object Nip85ExternalIdKindTag {
    const val TAG_NAME = "k"

    fun parse(tag: Array<String>): String? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return tag[1]
    }
}

/**
 * One NIP-10040 entry, `["<kind>:<metric>", <pubkey>, <relay>]`, without its relay: the [service]
 * a user trusts [pubkey] to sign. Main's [ServiceProviderTag.parse] drops the whole entry when
 * the relay is missing or does not normalize, and normalizes it through Quartz's global,
 * synchronized relay-url cache when it does. The relay only says where to fetch the provider's
 * assertions; the trust in the provider is stated without it, and no link uses it. The service
 * is bounded as main bounds it: only NIP-85's own assertion kinds
 * ([ServiceProviderTag.ASSERTION_KINDS]), so a neighbouring spec's `3039x:<name>` delegation
 * is not read as one.
 */
internal class Nip85ServiceProviderTag(
    val service: ServiceType,
    val pubkey: HexKey,
) {
    /** The tag's name is the service it delegates (`30382:rank`): unlike most tags, it varies. */
    fun tagName() = service.toValue()

    /** The service the provider is trusted for, on the link to the provider. */
    fun linkProps() = ServiceProps(service.toValue())

    companion object {
        fun parse(tag: Array<String>): Nip85ServiceProviderTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0].isNotEmpty()) { return null }
            ensure(tag[1].length == 64) { return null }
            val service = ServiceType.parse(tag[0]) ?: return null
            ensure(service.kind in ServiceProviderTag.ASSERTION_KINDS) { return null }
            return Nip85ServiceProviderTag(service, tag[1])
        }
    }
}

/**
 * The scores a NIP-85 event, address or external-id assertion (30383-30385) states about its
 * subject, each read by its metric tag ([RankTag], [CommentCountTag], …): the props of its
 * `SUBJECT` link. The first of each wins, as the classes' accessors read them; one walk reads all.
 */
internal fun TagArray.nip85ContentSubjectProps(): SubjectProps {
    var rank: Int? = null
    var commentCount: Int? = null
    var quoteCount: Int? = null
    var repostCount: Int? = null
    var reactionCount: Int? = null
    var zapCount: Int? = null
    var zapAmount: Long? = null
    for (tag in this) {
        when (tag.getOrNull(0)) {
            RankTag.TAG_NAME -> if (rank == null) rank = RankTag.parse(tag)
            CommentCountTag.TAG_NAME -> if (commentCount == null) commentCount = CommentCountTag.parse(tag)
            QuoteCountTag.TAG_NAME -> if (quoteCount == null) quoteCount = QuoteCountTag.parse(tag)
            RepostCountTag.TAG_NAME -> if (repostCount == null) repostCount = RepostCountTag.parse(tag)
            ReactionCountTag.TAG_NAME -> if (reactionCount == null) reactionCount = ReactionCountTag.parse(tag)
            ZapCountTag.TAG_NAME -> if (zapCount == null) zapCount = ZapCountTag.parse(tag)
            ZapAmountTag.TAG_NAME -> if (zapAmount == null) zapAmount = ZapAmountTag.parse(tag)
        }
    }
    return SubjectProps(
        rank = rank,
        commentCount = commentCount,
        quoteCount = quoteCount,
        repostCount = repostCount,
        reactionCount = reactionCount,
        zapCount = zapCount,
        zapAmount = zapAmount,
    )
}

/**
 * The public scores a 30382 card states about `aboutUser`, each read by its metric tag's parser
 * (the first of each wins, as [UserAssertionEvent]'s accessors read them) in ONE walk: the
 * accessors walk the tags once per metric, seventeen times per card, and trust providers publish
 * cards by the hundred thousand.
 */
internal fun UserAssertionEvent.nip85SubjectProps(): SubjectProps {
    var rank: Int? = null
    var followers: Int? = null
    var hops: Int? = null
    var firstCreatedAt: Long? = null
    var postCount: Int? = null
    var replyCount: Int? = null
    var reactionsCount: Int? = null
    var zapAmountReceived: Long? = null
    var zapAmountSent: Long? = null
    var zapCountReceived: Int? = null
    var zapCountSent: Int? = null
    var zapAvgAmountDayReceived: Long? = null
    var zapAvgAmountDaySent: Long? = null
    var reportsCountReceived: Int? = null
    var reportsCountSent: Int? = null
    var activeHoursStart: Int? = null
    var activeHoursEnd: Int? = null
    for (tag in tags) {
        when (tag.getOrNull(0)) {
            RankTag.TAG_NAME -> {
                if (rank == null) rank = RankTag.parse(tag)
            }

            FollowerCountTag.TAG_NAME -> {
                if (followers == null) followers = FollowerCountTag.parse(tag)
            }

            HopsTag.TAG_NAME -> {
                if (hops == null) hops = HopsTag.parse(tag)
            }

            FirstCreatedAtTag.TAG_NAME -> {
                if (firstCreatedAt == null) firstCreatedAt = FirstCreatedAtTag.parse(tag)
            }

            PostCountTag.TAG_NAME -> {
                if (postCount == null) postCount = PostCountTag.parse(tag)
            }

            ReplyCountTag.TAG_NAME -> {
                if (replyCount == null) replyCount = ReplyCountTag.parse(tag)
            }

            ReactionsCountTag.TAG_NAME -> {
                if (reactionsCount == null) reactionsCount = ReactionsCountTag.parse(tag)
            }

            ZapAmountReceivedTag.TAG_NAME -> {
                if (zapAmountReceived == null) zapAmountReceived = ZapAmountReceivedTag.parse(tag)
            }

            ZapAmountSentTag.TAG_NAME -> {
                if (zapAmountSent == null) zapAmountSent = ZapAmountSentTag.parse(tag)
            }

            ZapCountReceivedTag.TAG_NAME -> {
                if (zapCountReceived == null) zapCountReceived = ZapCountReceivedTag.parse(tag)
            }

            ZapCountSentTag.TAG_NAME -> {
                if (zapCountSent == null) zapCountSent = ZapCountSentTag.parse(tag)
            }

            ZapAvgAmountDayReceivedTag.TAG_NAME -> {
                if (zapAvgAmountDayReceived ==
                    null
                ) {
                    zapAvgAmountDayReceived = ZapAvgAmountDayReceivedTag.parse(tag)
                }
            }

            ZapAvgAmountDaySentTag.TAG_NAME -> {
                if (zapAvgAmountDaySent == null) zapAvgAmountDaySent = ZapAvgAmountDaySentTag.parse(tag)
            }

            ReportsCountReceivedTag.TAG_NAME -> {
                if (reportsCountReceived == null) reportsCountReceived = ReportsCountReceivedTag.parse(tag)
            }

            ReportsCountSentTag.TAG_NAME -> {
                if (reportsCountSent == null) reportsCountSent = ReportsCountSentTag.parse(tag)
            }

            ActiveHoursStartTag.TAG_NAME -> {
                if (activeHoursStart == null) activeHoursStart = ActiveHoursStartTag.parse(tag)
            }

            ActiveHoursEndTag.TAG_NAME -> {
                if (activeHoursEnd == null) activeHoursEnd = ActiveHoursEndTag.parse(tag)
            }
        }
    }
    return SubjectProps(
        rank = rank,
        followers = followers,
        hops = hops,
        firstCreatedAt = firstCreatedAt,
        postCount = postCount,
        replyCount = replyCount,
        reactionsCount = reactionsCount,
        zapAmountReceived = zapAmountReceived,
        zapAmountSent = zapAmountSent,
        zapCountReceived = zapCountReceived,
        zapCountSent = zapCountSent,
        zapAvgAmountDayReceived = zapAvgAmountDayReceived,
        zapAvgAmountDaySent = zapAvgAmountDaySent,
        reportsCountReceived = reportsCountReceived,
        reportsCountSent = reportsCountSent,
        activeHoursStart = activeHoursStart,
        activeHoursEnd = activeHoursEnd,
    )
}
