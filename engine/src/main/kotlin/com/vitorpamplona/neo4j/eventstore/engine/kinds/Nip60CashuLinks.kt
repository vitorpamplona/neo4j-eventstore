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
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip60Cashu.history.CashuSpendingHistoryEvent
import com.vitorpamplona.quartz.nip60Cashu.history.TokenReference
import com.vitorpamplona.quartz.nip60Cashu.quote.CashuMintQuoteEvent
import com.vitorpamplona.quartz.nip60Cashu.token.CashuTokenEvent
import com.vitorpamplona.quartz.nip60Cashu.wallet.CashuWalletEvent

/** Quartz's `nip60Cashu` classes. */
internal fun KindMappers.Builder.nip60Cashu() {
    // NIP-60 marks each public `e` with what the spend did to it. Only `redeemed` (a NIP-61 nutzap)
    // is meant to stay public; `created` and `destroyed` token events are normally encrypted and so
    // invisible here. The `p` is the redeemed nutzap's sender.
    on<CashuSpendingHistoryEvent> { e ->
        each(e.tags, TokenReference::parseFromTag) {
            val relation =
                when (it.marker) {
                    TokenReference.MARKER_REDEEMED -> Relation.REDEEMED
                    TokenReference.MARKER_CREATED -> Relation.CREATED
                    else -> Relation.DESTROYED
                }
            // A token reference is an `e` tag; main's TokenReference has no TAG_NAME of its own.
            event(relation, it.eventId, ETag.TAG_NAME)
        }
        each(e.tags, PTag::parse) { user(Relation.REDEEMED_AUTHOR, it, PTag.TAG_NAME) }
    }

    free<CashuMintQuoteEvent>()
    free<CashuTokenEvent>()
    free<CashuWalletEvent>()
}
