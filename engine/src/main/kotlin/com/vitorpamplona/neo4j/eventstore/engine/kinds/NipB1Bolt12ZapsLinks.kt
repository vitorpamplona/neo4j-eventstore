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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapProps
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.intent.Bolt12ZapIntentEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.offer.Bolt12OfferListEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.tags.PayerTag
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.zap.Bolt12ZapEvent

/** Quartz's `nipB1Bolt12Zaps` classes. */
internal fun KindMappers.Builder.nipB1Bolt12Zaps() {
    // NIP-B1: an intent is not a payment, but it names the same targets its 9736 will; the would-be sender is the author.
    on<Bolt12ZapIntentEvent> { e ->
        val props = ZapProps(e.amount())
        each(e.tags, PTag::parse) { user(Relation.ZAP_RECIPIENT, it, PTag.TAG_NAME, props) }
        each(e.tags, ETag::parse) { event(Relation.ZAPPED, it, ETag.TAG_NAME, props) }
        each(e.tags, ATag::parse) { address(Relation.ZAPPED, it, ATag.TAG_NAME, props) }
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
    }

    // NIP-B1: the recipient (`p`), the payer (`P`, absent on anonymous zaps) and the zapped content. The amount is verified against the payer proof, not by links.
    on<Bolt12ZapEvent> { e ->
        val props = ZapProps(e.amount())
        each(e.tags, PTag::parse) { user(Relation.ZAP_RECIPIENT, it, PTag.TAG_NAME, props) }
        each(e.tags, PayerTag::parse) { user(Relation.ZAP_SENDER, it, PayerTag.TAG_NAME) }
        each(e.tags, ETag::parse) { event(Relation.ZAPPED, it, ETag.TAG_NAME, props) }
        each(e.tags, ATag::parse) { address(Relation.ZAPPED, it, ATag.TAG_NAME, props) }
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
    }

    free<Bolt12OfferListEvent>()
}
