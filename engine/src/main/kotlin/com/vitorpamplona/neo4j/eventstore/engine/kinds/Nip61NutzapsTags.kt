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

import com.vitorpamplona.quartz.nip61Nutzaps.nutzap.NutzapEvent
import com.vitorpamplona.quartz.nip61Nutzaps.nutzap.claimedSatsTotal

/** NUT-00's base unit, and the one a nutzap without a `unit` tag is denominated in. Main's `UnitTag` has no constant for it. */
internal const val NIP61_SAT_UNIT = "sat"

/**
 * [claimedSatsTotal] in millisats, when the nutzap claims any and is sat-denominated (NUT-00's
 * default): other units are minor units of a fiat currency, with no msat value. Main has no
 * `claimedMsats()`.
 */
internal fun NutzapEvent.nip61ClaimedMsats(): Long? {
    if ((unit() ?: NIP61_SAT_UNIT) != NIP61_SAT_UNIT) return null
    val sats = claimedSatsTotal()
    return if (sats > 0) sats * 1000 else null
}
