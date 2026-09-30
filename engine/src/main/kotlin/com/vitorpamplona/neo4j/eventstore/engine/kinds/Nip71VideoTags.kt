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
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip71Video.credits.CreditTarget
import com.vitorpamplona.quartz.nip71Video.credits.VideoCredit
import com.vitorpamplona.quartz.nip71Video.credits.VideoCredits
import com.vitorpamplona.quartz.nip71Video.tags.TextTrackTag
import com.vitorpamplona.quartz.utils.ensure

// What the NIP-71 mappers need that the pinned Quartz lacks.

/** A [VideoCredit.label] that is a passing mention, not a credit. Main's `VideoCredit` has no constants for its labels. */
internal const val NIP71_MENTION_LABEL = "mention"

/** A [VideoCredit.label] crediting an inspiration. */
internal const val NIP71_INSPIRED_BY_LABEL = "inspired-by"

/**
 * A video's credit tag (`p`, `a` or `e` with a label in the marker slot), read as main's
 * [VideoCredits.parse] reads it but for one slot: main takes slot 2 as the label whenever it is
 * not a relay, so the empty relay placeholder NIP-01 tags carry (`["p", <pubkey>, "", "inspired-by"]`)
 * becomes a blank label, dropped, and the real label in slot 3 is lost: the credit reads as a bare
 * participant, an `e` credit vanishes and an `a` one turns into a mention. Here a blank slot 2 is
 * the empty relay hint it is, and the label follows it. A `root`/`reply` marker on an `e`/`a` is
 * NIP-10 threading, never a credit; an `e` without a label says nothing (both as main).
 */
internal object Nip71CreditTag {
    private val THREADING_MARKERS = setOf("root", "reply")

    fun parse(tag: Array<String>): VideoCredit? {
        ensure(tag.has(1)) { return null }
        return when (tag[0]) {
            PTag.TAG_NAME -> {
                if (tag[1].length != 64) return null
                VideoCredit(CreditTarget.Person(tag[1], relayHint(tag)), label(tag))
            }

            ATag.TAG_NAME -> {
                val address = ATag.parse(tag[1], relayHint(tag)?.url) ?: return null
                val label = label(tag)
                if (label in THREADING_MARKERS) null else VideoCredit(CreditTarget.Video(address), label)
            }

            ETag.TAG_NAME -> {
                if (tag[1].length != 64) return null
                val label = label(tag)
                if (label == null || label in THREADING_MARKERS) null else VideoCredit(CreditTarget.Event(tag[1], relayHint(tag)), label)
            }

            else -> {
                null
            }
        }
    }

    private fun isRelay(slot: String) = slot.length > 7 && RelayUrlNormalizer.isRelayUrl(slot)

    private fun relayHint(tag: Array<String>): NormalizedRelayUrl? {
        val slot = tag.getOrNull(2) ?: return null
        return if (isRelay(slot)) RelayUrlNormalizer.normalizeOrNull(slot) else null
    }

    /** Slot 2 is the label unless it holds a relay hint, empty or not; then the label is slot 3. */
    private fun label(tag: Array<String>): String? {
        val slot2 = tag.getOrNull(2)
        val label = if (slot2 == null || slot2.isBlank() || isRelay(slot2)) tag.getOrNull(3) else slot2
        return label?.ifBlank { null }
    }
}

/** What a video's `text-track` names, when it is a Nostr event rather than a plain WebVTT URL. */
internal sealed interface Nip71TextTrackTarget {
    /** A caption event by its address (divine.video's 39307). */
    data class ByAddress(
        val address: Address,
    ) : Nip71TextTrackTarget

    /** A caption event by its id. */
    data class ById(
        val eventId: HexKey,
    ) : Nip71TextTrackTarget
}

private val NIP71_NIP19_PREFIXES = listOf("nostr:", "nevent1", "naddr1", "note1")

/**
 * [TextTrackTag.ref] as the caption event it names: a hex id, a `kind:pubkey:d` coordinate, or a
 * `note`/`nevent`/`naddr` (decoded once). Null when it is a URL. Main's [TextTrackTag] keeps
 * `ref` untyped.
 */
internal fun TextTrackTag.nip71Target(): Nip71TextTrackTarget? {
    // A coordinate is longer than 64 (its pubkey alone is), so a 64-long ref can only be an id.
    if (ref.length == 64) return Nip71TextTrackTarget.ById(ref)
    if (ref.firstOrNull()?.isDigit() == true) return Address.parse(ref)?.let { Nip71TextTrackTarget.ByAddress(it) }
    if (NIP71_NIP19_PREFIXES.none { ref.startsWith(it) }) return null
    return when (val entity = Nip19Parser.uriToRoute(ref)?.entity) {
        is NAddress -> Nip71TextTrackTarget.ByAddress(entity.address())
        is NEvent -> Nip71TextTrackTarget.ById(entity.hex)
        is NNote -> Nip71TextTrackTarget.ById(entity.hex)
        else -> null
    }
}
