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
package com.vitorpamplona.neo4j.eventstore.engine.derive

import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip56Reports.ReportType
import com.vitorpamplona.quartz.nip56Reports.tags.DefaultReportTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAddressTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAuthorTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedEventTag
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.util.Locale

/**
 * The few values graph queries filter or rank on, lifted out of bodies the projection does not
 * keep (spec §4.3). Each is small, bounded, and additive to the schema.
 */
object Extractors {
    const val CONTENT = "content"
    const val MSATS = "msats"
    const val TITLE = "title"
    const val REPORT = "report"
    const val REPORT_RAW = "report_raw"
    const val SCOPE = "scope"
    const val RANK = "rank"
    const val FOLLOWERS = "followers"

    val USER_FIELDS = listOf("name", "display_name", "nip05")

    private const val MAX_REACTION_BYTES = 32

    /** 21M BTC in msats: no real amount is larger, and a larger one would overflow a Cypher `sum()`. */
    const val MAX_MSATS = 2_100_000_000_000_000_000L
    private val json = Json { isLenient = true }

    /** Values on the event node itself. */
    fun nodeValues(
        event: Event,
        policy: GraphPolicy,
    ): Map<String, Any> {
        val out = HashMap<String, Any>()
        when (event.kind) {
            // A kind 0 keeps the names it sets on its author on its own node too: the author's
            // names can then be restored from whichever kind 0 is still held (Neo4jGraphIndex).
            0 -> {
                authorValues(event, policy)?.let { out.putAll(it) }
            }

            // The reaction symbol: "+", "-", an emoji, or a :shortcode:.
            7 -> {
                val c = event.content
                if (c.isNotEmpty() && c.encodeToByteArray().size <= MAX_REACTION_BYTES) out[CONTENT] = c
            }

            9735 -> {
                (event as? ZapReceiptEvent)?.let { receipt ->
                    runCatching { receipt.amount() }.getOrNull()?.let { sats -> toMsats(sats)?.let { out[MSATS] = it } }
                }
            }

            // A zap request states its amount in msats in an `amount` tag.
            9734 -> {
                tagValue(event, "amount")?.toLongOrNull()?.let { if (it in 0..MAX_MSATS) out[MSATS] = it }
            }

            30023, 30311 -> {
                tagValue(event, "title")?.let { out[TITLE] = truncateUtf8(it, policy.maxCuratedBytes) }
            }

            34550 -> {
                (tagValue(event, "title") ?: tagValue(event, "name"))?.let { out[TITLE] = truncateUtf8(it, policy.maxCuratedBytes) }
            }
        }
        return out
    }

    /**
     * Values on the edge a single-letter tag produced. [report] is the event's [ReportFacts]
     * for a kind 1984 (read once per event, not once per tag).
     */
    fun edgeValues(
        event: Event,
        name: String,
        tag: Array<String>,
        target: NodeRef,
        policy: GraphPolicy,
        report: ReportFacts? = null,
    ): Map<String, Any>? =
        when {
            // NIP-56. Three values, because report queries filter on all three:
            //  - `report`: the CATEGORY, as Quartz's ReportEvent reads it — the tag's own type
            //    (slot 2, or slot 3 when slot 2 is a relay hint or blank), normalized to a
            //    ReportType code (localized labels like "Spam 📣" fold in), else the report's
            //    default. Never a relay URL; an unrecognized type is `other`.
            //  - `report_raw`: the type AS WRITTEN (trimmed, lowercased): clients invent types
            //    ("swearing", "ai-generated") that the category folds into `other`, and a query
            //    that wants to keep or drop exactly those needs the text.
            //  - `scope` (on `p_1984`): what the report is ABOUT. `user` when it names no event,
            //    address or blob — a standing complaint about the person; otherwise the reported
            //    content's kind of thing, and the `p` is that content's author.
            event.kind == 1984 && (name == "p" || name == "e" || name == "a") -> {
                val facts = report ?: ReportFacts.of(event)
                val out = HashMap<String, Any>()
                runCatching {
                    when (name) {
                        "p" -> ReportedAuthorTag.parse(tag, facts.defaultType)?.type
                        "e" -> ReportedEventTag.parse(tag, facts.defaultType)?.type
                        else -> ReportedAddressTag.parse(tag, facts.defaultType)?.type
                    }
                }.getOrNull()?.let { out[REPORT] = it.code }
                (ReportFacts.ownRaw(tag) ?: facts.defaultRaw)?.let { out[REPORT_RAW] = it }
                if (name == "p") out[SCOPE] = facts.scope
                out.ifEmpty { null }
            }

            // NIP-85: the assertion's scores ride the edge to the subject they are about.
            event.kind == 30382 && name == "d" && target.kind == NodeKind.USER -> {
                val card = event as? UserAssertionEvent
                if (card == null) {
                    null
                } else {
                    val out = HashMap<String, Any>()
                    runCatching { card.rank() }.getOrNull()?.let { out[RANK] = it.toLong() }
                    runCatching { card.followerCount() }.getOrNull()?.let { out[FOLLOWERS] = it.toLong() }
                    out.ifEmpty { null }
                }
            }

            else -> {
                null
            }
        }

    /** Kind 0 only: the name / display_name / nip05 it sets on its author (null otherwise). */
    fun authorValues(
        event: Event,
        policy: GraphPolicy,
    ): Map<String, String>? {
        if (event.kind != 0) return null
        val obj = runCatching { json.parseToJsonElement(event.content) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (field in USER_FIELDS) {
            val value = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (value.isNotBlank()) out[field] = truncateUtf8(value, policy.maxCuratedBytes)
        }
        return out
    }

    private fun tagValue(
        event: Event,
        name: String,
    ): String? =
        event.tags
            .firstOrNull { it.size >= 2 && it[0] == name }
            ?.get(1)
            ?.takeIf { it.isNotEmpty() }

    /** What a NIP-56 report says as a whole: read once per event, shared by its tags' edges. */
    class ReportFacts(
        /** `user`, `address`, `event` or `blob` — see [edgeValues]. */
        val scope: String,
        /** ReportEvent.defaultReportType() (private there): the event-level type, else the first tag-level one, else spam. */
        val defaultType: ReportType,
        /** The raw text behind [defaultType], when some tag wrote one. */
        val defaultRaw: String?,
    ) {
        companion object {
            const val USER = "user"
            const val ADDRESS = "address"
            const val EVENT = "event"
            const val BLOB = "blob"
            private const val MAX_RAW_BYTES = 64
            private val REPORTED = setOf("p", "e", "a", "x")

            fun of(event: Event): ReportFacts {
                var hasAddress = false
                var hasEvent = false
                var hasBlob = false
                for (tag in event.tags) {
                    if (tag.size < 2) continue
                    when (tag[0]) {
                        "a" -> if (canonicalAddress(tag[1]) != null) hasAddress = true
                        "e" -> if (isCanonicalHex64(tag[1])) hasEvent = true
                        "x" -> if (tag[1].isNotBlank()) hasBlob = true
                    }
                }
                // An addressable post is reported with its `e` (the version) AND its `a`: the
                // address is what the report is about.
                val scope =
                    when {
                        hasAddress -> ADDRESS
                        hasEvent -> EVENT
                        hasBlob -> BLOB
                        else -> USER
                    }
                val defaultType =
                    runCatching {
                        event.tags.firstNotNullOfOrNull(DefaultReportTag::parse)
                            ?: event.tags.firstNotNullOfOrNull {
                                ReportedAuthorTag.parse(it)?.type
                                    ?: ReportedEventTag.parse(it)?.type
                                    ?: ReportedAddressTag.parse(it)?.type
                            }
                    }.getOrNull() ?: ReportType.SPAM
                val defaultRaw =
                    event.tags.firstNotNullOfOrNull { t ->
                        if (t.size >= 2 &&
                            t[0] == DefaultReportTag.TAG_NAME
                        ) {
                            normalizeRaw(t[1])
                        } else {
                            null
                        }
                    }
                        ?: event.tags.firstNotNullOfOrNull { t -> if (t.isNotEmpty() && t[0] in REPORTED) ownRaw(t) else null }
                return ReportFacts(scope, defaultType, defaultRaw)
            }

            /** The type [tag] itself writes, by Quartz's layout: slot 3 when slot 2 is blank or a relay hint, else slot 2. */
            fun ownRaw(tag: Array<String>): String? {
                if (tag.size < 3) return null
                val hint = tag[2].isBlank() || (tag[2].length > 7 && RelayUrlNormalizer.isRelayUrl(tag[2]))
                val slot = if (hint) 3 else 2
                return tag.getOrNull(slot)?.let { normalizeRaw(it) }
            }

            private fun normalizeRaw(value: String): String? {
                val v = value.trim().lowercase(Locale.ROOT)
                if (v.isEmpty() || LinkRules.carriesNsec(v)) return null
                return truncateUtf8(v, MAX_RAW_BYTES)
            }
        }
    }

    // longValueExact: a plain toLong() keeps the low 64 bits of a crafted huge bolt11 amount.
    private fun toMsats(sats: BigDecimal): Long? =
        runCatching { sats.multiply(BigDecimal(1000)).longValueExact() }.getOrNull()?.takeIf { it in 0..MAX_MSATS }
}
