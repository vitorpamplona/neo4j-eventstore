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
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.utils.ensure

/**
 * An `e` or `a` tag of a NIP-54 event, with the marker NIP-54 and the clients extending it put in
 * the fourth slot: `fork` and `defer` on articles, `source` (or `fork`) on merge requests,
 * `result` and `request` on merge acceptances. Main has no parser that reads that slot as written:
 * `MarkedETag` searches several slots for NIP-10 markers, and these take no part in threading.
 */
internal sealed interface Nip54WikiReference {
    val marker: String?

    data class EventRef(
        val eventId: HexKey,
        override val marker: String? = null,
    ) : Nip54WikiReference

    data class AddressRef(
        val address: ATag,
        override val marker: String? = null,
    ) : Nip54WikiReference
}

/** Parses [Nip54WikiReference]s: `["e", "<id>", "<relay>", "<marker>"]`, `["a", "<kind>:<pubkey>:<d>", "<relay>", "<marker>"]`. */
internal object Nip54WikiReferenceTag {
    const val MARKER_SLOT = 3
    const val FORK_MARKER = "fork"
    const val DEFER_MARKER = "defer"

    /** Either kind of reference, in one walk, so a mapper keeps its tags' order. */
    fun parse(tag: Array<String>): Nip54WikiReference? = parseEvent(tag) ?: parseAddress(tag)

    fun parseEvent(tag: Array<String>): Nip54WikiReference.EventRef? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == ETag.TAG_NAME) { return null }
        ensure(tag[1].length == 64) { return null }
        return Nip54WikiReference.EventRef(tag[1], pickMarker(tag))
    }

    fun parseAddress(tag: Array<String>): Nip54WikiReference.AddressRef? {
        val address = ATag.parse(tag) ?: return null
        return Nip54WikiReference.AddressRef(address, pickMarker(tag))
    }

    private fun pickMarker(tag: Array<String>) = tag.getOrNull(MARKER_SLOT)?.ifEmpty { null }
}
