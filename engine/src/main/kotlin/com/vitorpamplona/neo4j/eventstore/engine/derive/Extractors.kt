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
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The few values graph queries filter or rank on, lifted out of bodies the projection does not
 * keep (spec §4.3). Each is small, bounded, and additive to the schema.
 */
object Extractors {
    const val CONTENT = "content"
    const val MSATS = "msats"
    const val TITLE = "title"
    const val REPORT = "report"
    const val RANK = "rank"
    const val FOLLOWERS = "followers"

    val USER_FIELDS = listOf("name", "display_name", "nip05")

    private const val MAX_REACTION_BYTES = 32
    private const val MAX_REPORT_BYTES = 32
    private val json = Json { isLenient = true }

    /** Values on the event node itself. */
    fun nodeValues(
        event: Event,
        policy: GraphPolicy,
    ): Map<String, Any> {
        val out = HashMap<String, Any>()
        when (event.kind) {
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
                tagValue(event, "amount")?.toLongOrNull()?.let { if (it >= 0) out[MSATS] = it }
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

    /** Values on the edge a single-letter tag produced. */
    fun edgeValues(
        event: Event,
        name: String,
        tag: Array<String>,
        target: NodeRef,
        policy: GraphPolicy,
    ): Map<String, Any>? =
        when {
            // NIP-56: the report type is the tag's third element, on `p` and `e` alike.
            event.kind == 1984 && (name == "p" || name == "e" || name == "a") -> {
                val type = tag.getOrNull(2)
                if (!type.isNullOrEmpty() && type.encodeToByteArray().size <= MAX_REPORT_BYTES) mapOf(REPORT to type) else null
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

    private fun toMsats(sats: BigDecimal): Long? = runCatching { sats.multiply(BigDecimal(1000)).toLong() }.getOrNull()?.takeIf { it >= 0 }
}
