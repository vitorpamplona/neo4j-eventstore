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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapProps
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nipBCOnchainZaps.zap.OnchainZapEvent
import com.vitorpamplona.quartz.nipBCOnchainZaps.zap.tags.BitcoinTxIdTag

/** Quartz's `nipBCOnchainZaps` classes. */
internal fun KindMappers.Builder.nipBCOnchainZaps() {
    // NIP-BC: the recipient and the zapped content; the sender is the author. The amount is the sender's claim until it is checked on chain, and `i` names the transaction.
    on<OnchainZapEvent> { e ->
        val props = ZapProps(e.claimedAmountInSats()?.let { it * 1000 })
        each(e.tags, PTag::parse) { user(Relation.ZAP_RECIPIENT, it, PTag.TAG_NAME, props) }
        each(e.tags, ETag::parse) { event(Relation.ZAPPED, it, ETag.TAG_NAME, props) }
        each(e.tags, ATag::parse) { address(Relation.ZAPPED, it, ATag.TAG_NAME, props) }
        each(e.tags, BitcoinTxIdTag::parseScope) { value(Relation.TRANSACTION, ValueType.BITCOIN_TX, it, BitcoinTxIdTag.TAG_NAME) }
        each(e.tags, KindTag::parse) { value(Relation.ZAPPED_KIND, ValueType.KIND, it.toString(), KindTag.TAG_NAME) }
    }
}
