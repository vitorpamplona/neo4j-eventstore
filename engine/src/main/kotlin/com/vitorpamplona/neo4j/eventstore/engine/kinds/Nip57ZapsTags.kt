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
import com.vitorpamplona.quartz.utils.BigDecimal
import com.vitorpamplona.quartz.utils.ensure
import com.vitorpamplona.quartz.utils.toDoubleValue
import com.vitorpamplona.quartz.utils.toLongValue

/**
 * The `amount` tag of a NIP-57 zap request: the amount to pay, in **millisats**. A zap moves a
 * positive amount, so anything else is no amount. Main has no parser for it.
 */
internal object Nip57AmountTag {
    const val TAG_NAME = "amount"

    fun parse(tag: Array<String>): Long? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return tag[1].toLongOrNull()?.takeIf { it > 0 }
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

/** The requested amount ([Nip57AmountTag]), in millisats. */
internal fun ZapRequestEvent.nip57AmountMillisats() = tags.firstNotNullOfOrNull(Nip57AmountTag::parse)

private val NIP57_MSATS_PER_SAT = BigDecimal(1000)

/** An invoice amount in sats as whole msats, or null when it is not positive or does not fit a Long (a crafted invoice). */
internal fun nip57SatsToMsats(sats: BigDecimal?): Long? =
    sats
        ?.multiply(NIP57_MSATS_PER_SAT)
        ?.takeIf { it.signum() > 0 && it.toDoubleValue() < Long.MAX_VALUE.toDouble() }
        ?.toLongValue()
