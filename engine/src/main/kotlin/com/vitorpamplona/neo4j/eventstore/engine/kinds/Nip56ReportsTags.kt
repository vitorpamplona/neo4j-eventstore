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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ReportProps
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import com.vitorpamplona.quartz.nip56Reports.ReportType
import com.vitorpamplona.quartz.nip56Reports.tags.DefaultReportTag
import com.vitorpamplona.quartz.nip56Reports.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAddressTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAuthorTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedEventTag

/** One thing a NIP-56 report names ([value]: an event id, a pubkey, an address, a blob hash), with its category. */
internal class Nip56Reported<T>(
    val value: T,
    val props: ReportProps,
)

/**
 * What a NIP-56 report names, read in ONE pass over its tags: the reported events (`e`), people
 * (`p`), addresses (`a`) and blobs (`x`), each with its category as [ReportProps]: `report`, the
 * type as Quartz reads it (a `ReportType` code), and `report_raw`, the type as written (trimmed and
 * lowercased: clients invent types that fold into `other`).
 *
 * A tag's own type wins; a tag that writes none takes the report's default, found as
 * `ReportEvent`'s private `defaultReportType` finds it: the legacy `report` tag, else the first
 * `p`/`e`/`a` that writes a type, else spam (whose `report_raw` stays absent: nothing wrote it).
 * A BLANK type slot writes no type, for `x` as for the others: main's `HashSha256Tag.parse` feeds
 * a blank to `ReportType.parseOrNull`, which folds it into `other`.
 *
 * Main's parsers keep only the folded [ReportType], and read the layout (`ReportTagLayout`,
 * internal to Quartz) by normalizing the relay hint through Quartz's global, synchronized
 * relay-url cache, up to three times per tag. The layout is mirrored here by the hint's SHAPE
 * alone, as its KDoc defines it ("a slot that parses as a relay URL is a hint"), and nothing is
 * normalized: no link uses the hint.
 */
internal class Nip56Report(
    val events: List<Nip56Reported<HexKey>>,
    val authors: List<Nip56Reported<HexKey>>,
    val addresses: List<Nip56Reported<Address>>,
    val hashes: List<Nip56Reported<HexKey>>,
) {
    /**
     * Whether the report names any content: an event, an address or a blob. One that names none
     * is a complaint about the person its `p` names; otherwise that `p` is the content's author.
     */
    val isAboutContent get() = events.isNotEmpty() || addresses.isNotEmpty() || hashes.isNotEmpty()
}

/** The raw-type readers of the NIP-56 tags, which main does not expose. */
internal object Nip56ReportTags {
    /** Quartz's `ReportTagLayout`: slot 2 is a relay hint when it has a relay URL's shape. */
    private fun isRelayHint(slot: String) = slot.length > 7 && RelayUrlNormalizer.isRelayUrl(slot)

    /**
     * A type as written, trimmed and lowercased. Clients coin their own types, which
     * [ReportType.parseOrNull] folds into [ReportType.OTHER]; this keeps what they said.
     */
    fun normalizeRawType(value: String) = value.trim().lowercase().ifEmpty { null }

    /**
     * The type slot a `p`/`e`/`a` writes, as written: slot 3 after a blank or relay-hint slot 2,
     * else slot 2. Null when the tag writes none (no slot, or a blank one).
     */
    fun pointerTypeSlot(tag: Array<String>): String? {
        if (!tag.has(2)) return null
        val slot = if (tag[2].isBlank() || isRelayHint(tag[2])) 3 else 2
        return tag.getOrNull(slot)?.takeUnless { it.isBlank() }
    }

    /** A blob's type is always in slot 2. Null when the tag writes none. */
    fun hashTypeSlot(tag: Array<String>): String? = tag.getOrNull(2)?.takeUnless { it.isBlank() }

    /** The legacy `report` tag's type as written; null for any other tag, or a blank one. */
    @Suppress("DEPRECATION")
    fun defaultTypeSlot(tag: Array<String>): String? {
        if (!tag.has(1) || tag[0] != DefaultReportTag.TAG_NAME) return null
        return tag[1].takeUnless { it.isBlank() }
    }

    fun eventId(tag: Array<String>) = ReportedEventTag.parseId(tag)

    fun pubKey(tag: Array<String>) = ReportedAuthorTag.parseKey(tag)

    fun address(tag: Array<String>): Address? {
        if (!tag.has(1) || tag[0] != ReportedAddressTag.TAG_NAME || tag[1].isEmpty()) return null
        return Address.parse(tag[1])
    }

    fun hash(tag: Array<String>): HexKey? {
        if (!tag.has(1) || tag[0] != HashSha256Tag.TAG_NAME || tag[1].length != 64) return null
        return tag[1]
    }
}

/** A reported value with the type its [tag] writes, before the report's default is known. */
private class Nip56Pending<T>(
    val value: T,
    val type: String?,
    val tag: Array<String>,
)

/** [Nip56Report] of this report: one walk over its tags. */
internal fun ReportEvent.nip56Report(): Nip56Report {
    val events = ArrayList<Nip56Pending<HexKey>>()
    val authors = ArrayList<Nip56Pending<HexKey>>()
    val addresses = ArrayList<Nip56Pending<Address>>()
    val hashes = ArrayList<Nip56Pending<HexKey>>()
    var legacyType: String? = null
    var firstPointerType: String? = null

    for (tag in tags) {
        val id = Nip56ReportTags.eventId(tag)
        val key = if (id == null) Nip56ReportTags.pubKey(tag) else null
        val address = if (id == null && key == null) Nip56ReportTags.address(tag) else null
        if (id != null || key != null || address != null) {
            val type = Nip56ReportTags.pointerTypeSlot(tag)
            if (firstPointerType == null) firstPointerType = type
            when {
                id != null -> events.add(Nip56Pending(id, type, tag))
                key != null -> authors.add(Nip56Pending(key, type, tag))
                address != null -> addresses.add(Nip56Pending(address, type, tag))
            }
            continue
        }
        val hash = Nip56ReportTags.hash(tag)
        if (hash != null) {
            hashes.add(Nip56Pending(hash, Nip56ReportTags.hashTypeSlot(tag), tag))
        } else if (legacyType == null) {
            legacyType = Nip56ReportTags.defaultTypeSlot(tag)
        }
    }

    val defaultWritten = legacyType ?: firstPointerType
    val defaultProps = defaultWritten?.let { nip56Props(it, emptyArray()) } ?: ReportProps(ReportType.SPAM.code, null)

    fun <T> resolve(pending: List<Nip56Pending<T>>) =
        pending.map {
            Nip56Reported(
                it.value,
                it.type?.let { type -> nip56Props(type, it.tag) } ?: defaultProps,
            )
        }

    return Nip56Report(resolve(events), resolve(authors), resolve(addresses), resolve(hashes))
}

/**
 * A written type as props: its [ReportType] code (an unknown type folds into `other`) and the type
 * as written. [tag] is only for Quartz's log line about an unknown type.
 */
private fun nip56Props(
    written: String,
    tag: Array<String>,
) = ReportProps(
    (ReportType.parseOrNull(written, tag) ?: ReportType.OTHER).code,
    Nip56ReportTags.normalizeRawType(written),
)
