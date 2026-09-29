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
package com.vitorpamplona.neo4j.eventstore.cypher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A caller's query: `{"query": "...", "params": {...}, "hydrate": true}` (spec §8.1). */
data class CypherRequest(
    val query: String,
    val params: Map<String, Any?> = emptyMap(),
    val hydrate: Boolean = true,
) {
    companion object {
        private val KEYS = setOf("query", "params", "hydrate")

        /**
         * Parses the wire form. Unknown keys are an ERROR, not ignored: a typo (`"parms"`) must
         * not silently run the query without its parameters.
         */
        fun fromJson(body: String): CypherRequest {
            val obj = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")
            val unknown = obj.keys - KEYS
            require(unknown.isEmpty()) { "unknown keys: $unknown" }
            val query =
                (obj["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("missing \"query\"")
            val params =
                when (val p = obj["params"]) {
                    null, is JsonNull -> emptyMap()
                    is JsonObject -> p.mapValues { toKotlin(it.value) }
                    else -> throw IllegalArgumentException("\"params\" must be an object")
                }
            val hydrate = (obj["hydrate"] as? JsonPrimitive)?.booleanOrNull ?: true
            return CypherRequest(query, params, hydrate)
        }

        /** JSON → the plain values the driver accepts as parameters. */
        fun toKotlin(e: JsonElement): Any? =
            when (e) {
                is JsonNull -> null
                is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
                is JsonArray -> e.map { toKotlin(it) }
                is JsonObject -> e.mapValues { toKotlin(it.value) }
            }
    }
}
