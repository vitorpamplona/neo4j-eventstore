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
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import com.vitorpamplona.quartz.nip56Reports.ReportType
import com.vitorpamplona.quartz.nip56Reports.tags.BaseReportTag
import com.vitorpamplona.quartz.nip56Reports.tags.DefaultReportTag
import com.vitorpamplona.quartz.nip56Reports.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAddressTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAuthorTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedEventTag
import com.vitorpamplona.quartz.utils.ensure

/**
 * A NIP-56 pointer tag parsed by main's parser, with the report type AS WRITTEN beside it: main's
 * report tags keep only [BaseReportTag.type], which folds a type Quartz does not know into
 * [ReportType.OTHER].
 */
internal class Nip56ReportedTag<T : BaseReportTag>(
    val tag: T,
    val rawType: String?,
) {
    /** The report's category on the link to what this tag reports: the type's code and the type as written. */
    fun linkProps() = ReportProps(tag.type?.code, rawType)
}

/**
 * The raw-type readers of the NIP-56 tags. Main parses the type only into a [ReportType], and its
 * slot logic (`ReportTagLayout`) is internal to Quartz, so it is mirrored here.
 */
internal object Nip56ReportTags {
    /** Quartz's `ReportTagLayout.relayHint`: the relay hint at slot 2, or null under the legacy layout. */
    private fun relayHint(tag: Array<String>): NormalizedRelayUrl? {
        if (tag.has(2) && tag[2].length > 7 && RelayUrlNormalizer.isRelayUrl(tag[2])) {
            return RelayUrlNormalizer.normalizeOrNull(tag[2])
        }
        return null
    }

    /**
     * A type as written, trimmed and lowercased. Clients coin their own types, which
     * [ReportType.parseOrNull] folds into [ReportType.OTHER]; this keeps what they said.
     */
    fun normalizeRawType(value: String) = value.trim().lowercase().ifEmpty { null }

    /**
     * The type a `p`/`e`/`a` writes itself, as written, read from the slot the tag's type is read
     * from (slot 3 after a blank or relay-hint slot 2, else slot 2). Null when the tag writes none.
     */
    fun rawReportType(tag: Array<String>): String? {
        if (!tag.has(2)) return null
        val slot = if (tag[2].isBlank() || relayHint(tag) != null) 3 else 2
        return tag.getOrNull(slot)?.let(::normalizeRawType)
    }

    /** The legacy `report` tag's type, as written. */
    @Suppress("DEPRECATION")
    fun parseDefaultRaw(tag: Array<String>): String? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == DefaultReportTag.TAG_NAME) { return null }
        return normalizeRawType(tag[1])
    }

    /** [defaultType] and [defaultRaw] are the report's own type, for a tag that writes none. */
    fun parseEvent(
        tag: Array<String>,
        defaultType: ReportType?,
        defaultRaw: String?,
    ) = ReportedEventTag.parse(tag, defaultType)?.let { Nip56ReportedTag(it, rawReportType(tag) ?: defaultRaw) }

    /** See [parseEvent] for the defaults. */
    fun parseAuthor(
        tag: Array<String>,
        defaultType: ReportType?,
        defaultRaw: String?,
    ) = ReportedAuthorTag.parse(tag, defaultType)?.let { Nip56ReportedTag(it, rawReportType(tag) ?: defaultRaw) }

    /** See [parseEvent] for the defaults. */
    fun parseAddress(
        tag: Array<String>,
        defaultType: ReportType?,
        defaultRaw: String?,
    ) = ReportedAddressTag.parse(tag, defaultType)?.let { Nip56ReportedTag(it, rawReportType(tag) ?: defaultRaw) }

    /** A blob's type is always in slot 2. See [parseEvent] for the defaults. */
    fun parseHash(
        tag: Array<String>,
        defaultType: ReportType?,
        defaultRaw: String?,
    ) = HashSha256Tag.parse(tag, defaultType)?.let { Nip56ReportedTag(it, tag.getOrNull(2)?.let(::normalizeRawType) ?: defaultRaw) }
}

/**
 * The report's default type, as [ReportEvent]'s private `defaultReportType` finds it: the legacy
 * `report` tag, else the first `p`/`e`/`a` that writes one, else spam.
 */
@Suppress("DEPRECATION")
internal fun ReportEvent.nip56DefaultReportType(): ReportType =
    tags.firstNotNullOfOrNull(DefaultReportTag::parse)
        ?: tags.firstNotNullOfOrNull {
            ReportedAuthorTag.parse(it)?.type
                ?: ReportedEventTag.parse(it)?.type
                ?: ReportedAddressTag.parse(it)?.type
        } ?: ReportType.SPAM

/** The report's default type as written, found the way [nip56DefaultReportType] finds the type. */
internal fun ReportEvent.nip56DefaultReportRawType(): String? =
    tags.firstNotNullOfOrNull(Nip56ReportTags::parseDefaultRaw)
        ?: tags.firstNotNullOfOrNull {
            ReportedAuthorTag.parse(it)?.let { _ -> Nip56ReportTags.rawReportType(it) }
                ?: ReportedEventTag.parse(it)?.let { _ -> Nip56ReportTags.rawReportType(it) }
                ?: ReportedAddressTag.parse(it)?.let { _ -> Nip56ReportTags.rawReportType(it) }
        }

/**
 * Whether the report names any content: an event, an address or a blob. One that names none
 * is a complaint about the person its `p` names; otherwise that `p` is the content's author.
 */
internal fun ReportEvent.nip56IsAboutContent() =
    tags.fastAny {
        ReportedEventTag.parseId(
            it,
        ) != null || ReportedAddressTag.parseAddressId(it) != null || HashSha256Tag.parse(it) != null
    }
