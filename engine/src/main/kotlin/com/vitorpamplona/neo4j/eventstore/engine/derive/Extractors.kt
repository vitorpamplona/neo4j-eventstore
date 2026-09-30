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
import com.vitorpamplona.neo4j.eventstore.engine.schema.truncateUtf8
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The few values graph queries filter or rank on that live on NODES, lifted out of bodies the
 * projection does not keep (spec §4.3). Values that qualify one reference (a report's category,
 * an assertion's rank) ride its edge instead, as the relation's props (`vocab/props`).
 */
object Extractors {
    const val CONTENT = "content"
    const val MSATS = "msats"
    const val TITLE = "title"

    /**
     * The kind-0 fields kept on the profile's own node. Never copied onto its `:User`: a user's
     * names are one keyed hop away (`(:Address {id: '0:' + pk + ':'})<-[:ADDRESS]-(profile)`), and a
     * copy would have to be cleared, and restored from another held kind 0, on every removal.
     */
    val PROFILE_FIELDS = listOf("name", "display_name", "nip05")

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
            // The names a profile states, for lookups (`nip05`) and for showing a user.
            0 -> {
                out.putAll(profileValues(event, policy))
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

    /** A kind 0's [PROFILE_FIELDS] that are non-blank strings, each bounded to the curated size. */
    private fun profileValues(
        event: Event,
        policy: GraphPolicy,
    ): Map<String, String> {
        val obj = runCatching { json.parseToJsonElement(event.content) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (field in PROFILE_FIELDS) {
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

    // longValueExact: a plain toLong() keeps the low 64 bits of a crafted huge bolt11 amount.
    private fun toMsats(sats: BigDecimal): Long? =
        runCatching { sats.multiply(BigDecimal(1000)).longValueExact() }.getOrNull()?.takeIf { it in 0..MAX_MSATS }
}
