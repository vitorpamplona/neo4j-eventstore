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

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip71Video.credits.VideoCredit
import com.vitorpamplona.quartz.nip71Video.tags.TextTrackTag

// What the NIP-71 mappers need that the pinned Quartz lacks.

/** A [VideoCredit.label] that is a passing mention, not a credit. Main's `VideoCredit` has no constants for its labels. */
internal const val NIP71_MENTION_LABEL = "mention"

/** A [VideoCredit.label] crediting an inspiration. */
internal const val NIP71_INSPIRED_BY_LABEL = "inspired-by"

private val NIP71_NIP19_PREFIXES = listOf("nostr:", "nevent1", "naddr1", "note1")

/**
 * [TextTrackTag.ref] as the address of a caption event: a `kind:pubkey:d` coordinate or an
 * `naddr`. Null when it is a URL or names an event. Main's [TextTrackTag] keeps `ref` untyped.
 */
internal fun TextTrackTag.nip71Address(): Address? {
    if (ref.firstOrNull()?.isDigit() == true) return Address.parse(ref)
    return (nip71Nip19Entity() as? NAddress)?.address()
}

/** [TextTrackTag.ref] as the id of a caption event: a hex id, a `note` or an `nevent`. */
internal fun TextTrackTag.nip71EventId(): HexKey? {
    if (ref.length == 64) return ref
    return when (val entity = nip71Nip19Entity()) {
        is NEvent -> entity.hex
        is NNote -> entity.hex
        else -> null
    }
}

private fun TextTrackTag.nip71Nip19Entity() =
    if (NIP71_NIP19_PREFIXES.any {
            ref.startsWith(it)
        }
    ) {
        Nip19Parser.uriToRoute(ref)?.entity
    } else {
        null
    }
