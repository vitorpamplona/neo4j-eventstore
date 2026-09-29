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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent

/** Quartz's `nip57Zaps` classes. */
internal fun KindMappers.Builder.nip57Zaps() {
    // The decrypted request of a private zap: the same `e`/`a`/`p`/`k` as the public request it hides in.
    on<PrivateZapEvent> { e -> nip57ZapLinks(e.tags, null) }

    // NIP-57 Appendix E: the receipt copies the request's `e`/`a`/`p`/`k`, with the paid `msats`
    // (from the `bolt11` invoice) on the `ZAPPED` and `ZAP_RECIPIENT` links, and adds the zap
    // sender as `P`. The zap request embedded in `description` is JSON and is not read here.
    on<ZapReceiptEvent> { e -> nip57ZapLinks(e.tags, ZapProps(nip57SatsToMsats(e.amount)), withSender = true) }

    // NIP-57 Appendix A: the `e`/`a` is the `ZAPPED` content and the `p` the `ZAP_RECIPIENT`, both
    // with the requested `msats` (`nip57AmountMillisats`) when there is one; `k` is the zapped kind.
    on<ZapRequestEvent> { e -> nip57ZapLinks(e.tags, ZapProps(e.nip57AmountMillisats())) }
}

/**
 * The NIP-57 tags a zap request, its receipt and a decrypted private zap share: the `e`/`a` is
 * the `ZAPPED` content and the `p` the `ZAP_RECIPIENT` (NIP-57's "recipient"), each with
 * [props] (the `msats`, when the kind knows them); `k` is the zapped kind. With [withSender], the
 * receipt's `P` ([Nip57ZapSenderTag]) is the `ZAP_SENDER`: NIP-57 copies it from the zap
 * request's pubkey.
 */
private fun LinkBuilder.nip57ZapLinks(
    tags: TagArray,
    props: ZapProps?,
    withSender: Boolean = false,
) {
    each(tags, PTag::parse) { user(Relation.ZAP_RECIPIENT, it, PTag.TAG_NAME, props) }
    if (withSender) each(tags, Nip57ZapSenderTag::parse) { user(Relation.ZAP_SENDER, it, Nip57ZapSenderTag.TAG_NAME) }
    each(tags, ETag::parse) { event(Relation.ZAPPED, it, ETag.TAG_NAME, props) }
    each(tags, ATag::parse) { address(Relation.ZAPPED, it, ATag.TAG_NAME, props) }
    each(tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
}
