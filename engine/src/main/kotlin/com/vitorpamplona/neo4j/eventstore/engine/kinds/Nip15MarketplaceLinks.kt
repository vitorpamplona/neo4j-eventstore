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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.AuctionProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.BidProps
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip15Marketplace.auction.AuctionEvent
import com.vitorpamplona.quartz.nip15Marketplace.bid.BidEvent
import com.vitorpamplona.quartz.nip15Marketplace.bidConfirmation.BidConfirmationEvent
import com.vitorpamplona.quartz.nip15Marketplace.marketplace.MarketplaceEvent
import com.vitorpamplona.quartz.nip15Marketplace.product.ProductEvent
import com.vitorpamplona.quartz.nip15Marketplace.stall.StallEvent

/** Quartz's `nip15Marketplace` classes. */
internal fun KindMappers.Builder.nip15Marketplace() {
    // NIP-15: `t` tags are categories. The stall is named by `stall_id` inside the content JSON, which links do not parse.
    on<AuctionEvent> { e -> hashtags(e.tags) }
    on<ProductEvent> { e -> hashtags(e.tags) }

    // NIP-15: the auction is named by its event id (a 30020 version) and the bid amount is the content. The `p` is Quartz's notification of the auction's merchant.
    on<BidEvent> { e ->
        val props = AuctionProps(e.amount())
        each(e.tags, ETag::parse) { event(Relation.AUCTION, it, ETag.TAG_NAME, props) }
        each(e.tags, PTag::parse) { user(Relation.AUCTION_AUTHOR, it, PTag.TAG_NAME) }
    }

    // NIP-15 orders the `e` tags: the bid first, then its auction. The `p` is the bidder, which
    // Quartz adds to notify them. The confirmation's status rides on the bid link.
    on<BidConfirmationEvent> { e ->
        val data = e.confirmationData()
        val eTags = e.tags.mapNotNull(ETag::parse)
        event(Relation.BID, eTags.firstOrNull(), ETag.TAG_NAME, BidProps(data?.status, data?.durationExtension))
        event(Relation.AUCTION, eTags.getOrNull(1), ETag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.BID_AUTHOR, it, PTag.TAG_NAME) }
    }

    free<MarketplaceEvent>()
    free<StallEvent>()
}
