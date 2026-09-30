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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.utils.ensure
import java.math.BigDecimal

/**
 * The `amount` tag of a NIP-57 zap request: the amount to pay, in **millisats**. A zap moves a
 * positive amount, no more than [NIP57_MAX_MSATS], so anything else is no amount. Main has no
 * parser for it.
 */
internal object Nip57AmountTag {
    const val TAG_NAME = "amount"

    fun parse(tag: Array<String>): Long? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return nip57Msats(tag[1].toLongOrNull())
    }
}

/**
 * The uppercase `P` of a NIP-57 zap receipt: the zap's sender, copied from the zap request's
 * pubkey. Lowercase `p` is the recipient. Main has no parser for it.
 */
internal object Nip57ZapSenderTag {
    const val TAG_NAME = "P"

    fun parse(tag: Array<String>): HexKey? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].length == 64) { return null }
        return tag[1]
    }
}

/**
 * The `description` of a NIP-57 zap receipt: the zap request, as JSON. Main parses it into
 * `ZapReceiptEvent.zapRequest` (its private `description()` reads the tag); only its name is
 * needed here, as the `via` of the sender read from it.
 */
internal object Nip57DescriptionTag {
    const val TAG_NAME = "description"
}

/** The requested amount ([Nip57AmountTag]), in millisats. */
internal fun ZapRequestEvent.nip57AmountMillisats() = tags.firstNotNullOfOrNull(Nip57AmountTag::parse)

/**
 * 21M BTC in msats, the most a zap's `msats` can be: no real amount is larger, and a larger one
 * would overflow a Cypher `sum()`. The same bound the node's `msats` has (`Extractors.MAX_MSATS`,
 * in `derive/`, a layer this one cannot import; `Nip57ZapsLinksTest` holds the two equal).
 */
internal const val NIP57_MAX_MSATS = 2_100_000_000_000_000_000L

/**
 * [msats] as a zap link's `msats`, or null when it is no zap's amount: not positive, or above
 * [NIP57_MAX_MSATS] (a crafted request or invoice). The one converter every zap amount goes through.
 */
internal fun nip57Msats(msats: Long?): Long? = msats?.takeIf { it in 1..NIP57_MAX_MSATS }

private val NIP57_MSATS_PER_SAT = BigDecimal(1000)

/**
 * An invoice amount in sats as [nip57Msats], or null. `longValueExact`, as the node's converter
 * reads it: a plain `toLong()` keeps the low 64 bits of a crafted huge amount, and a fraction of
 * a msat is no valid bolt11 amount.
 */
internal fun nip57SatsToMsats(sats: BigDecimal?): Long? =
    sats?.let { runCatching { it.multiply(NIP57_MSATS_PER_SAT).longValueExact() }.getOrNull() }.let(::nip57Msats)
