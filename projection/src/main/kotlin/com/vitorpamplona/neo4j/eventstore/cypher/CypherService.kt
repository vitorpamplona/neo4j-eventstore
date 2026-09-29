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

import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.neo4j.driver.AccessMode
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Path
import org.neo4j.driver.types.Relationship
import java.security.MessageDigest

/**
 * Runs callers' read-only Cypher against the projection (spec §8): [CypherGuard] first, then a
 * READ transaction on the data database, streaming rows as JSON with `:Event:Stored` nodes
 * hydrated from the source of truth.
 *
 * NO resource limits in v1 (spec §8.2 layer 4): no timeout, row, byte or memory cap. A heavy
 * query runs to completion. Limits are set later from production measurements; who may call is
 * the relay's decision.
 */
class CypherService(
    private val driver: Driver,
    private val database: String = "neo4j",
    private val hydrator: Hydrator? = null,
    private val audit: CypherAudit? = null,
    private val guard: CypherGuard = CypherGuard(database),
    private val hydrateBatch: Int = 200,
) {
    sealed interface Outcome {
        data class Ok(
            val rows: Long,
            val elapsedMs: Long,
        ) : Outcome

        data class Rejected(
            val reason: String,
        ) : Outcome
    }

    /**
     * Only the guard's verdict — for an HTTP layer that must choose its status code BEFORE it
     * starts streaming a 200 (a streamed body cannot turn into a 400 halfway). Null = allowed.
     */
    suspend fun precheck(request: CypherRequest): Outcome.Rejected? =
        withContext(Dispatchers.IO) {
            val config =
                SessionConfig
                    .builder()
                    .withDatabase(database)
                    .withDefaultAccessMode(AccessMode.READ)
                    .build()
            driver.session(config).use { session ->
                (guard.check(session, request.query, request.params) as? CypherGuard.Verdict.Rejected)?.let { Outcome.Rejected(it.reason) }
            }
        }

    /**
     * Executes [request] and writes the JSON document `{"columns":[…],"rows":[[…],…],"elapsedMs":n}`
     * to [sink] in pieces (rows are never all held at once). On rejection nothing is written.
     */
    suspend fun execute(
        request: CypherRequest,
        caller: String?,
        sink: suspend (String) -> Unit,
    ): Outcome {
        val started = System.nanoTime()
        val config =
            SessionConfig
                .builder()
                .withDatabase(database)
                .withDefaultAccessMode(AccessMode.READ)
                .build()
        val outcome =
            withContext(Dispatchers.IO) {
                driver.session(config).use { session ->
                    when (val verdict = guard.check(session, request.query, request.params)) {
                        is CypherGuard.Verdict.Rejected -> {
                            Outcome.Rejected(verdict.reason)
                        }

                        CypherGuard.Verdict.Allowed -> {
                            session.beginTransaction().use { tx ->
                                val result = tx.run(request.query, request.params)
                                sink(
                                    "{\"columns\":" +
                                        JSON.encodeToString(JsonElement.serializer(), JsonArray(result.keys().map { JsonPrimitive(it) })) +
                                        ",\"rows\":[",
                                )
                                var rows = 0L
                                val batch = ArrayList<Record>(hydrateBatch)
                                while (result.hasNext()) {
                                    batch += result.next()
                                    if (batch.size >= hydrateBatch) {
                                        rows += flush(batch, request.hydrate, rows == 0L, sink)
                                        batch.clear()
                                    }
                                }
                                rows += flush(batch, request.hydrate, rows == 0L, sink)
                                tx.rollback() // read-only: never commit anything
                                val elapsed = (System.nanoTime() - started) / 1_000_000
                                sink("],\"elapsedMs\":$elapsed}")
                                Outcome.Ok(rows, elapsed)
                            }
                        }
                    }
                }
            }
        audit?.record(
            CypherAuditEntry(
                caller = caller,
                queryHash = sha256(request.query),
                paramNames = request.params.keys.sorted(),
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                rows = (outcome as? Outcome.Ok)?.rows ?: 0,
                outcome = if (outcome is Outcome.Rejected) "rejected: ${outcome.reason}" else "ok",
            ),
        )
        return outcome
    }

    /** [execute] into one string — for tests and small calls. */
    suspend fun query(
        request: CypherRequest,
        caller: String? = null,
    ): Pair<Outcome, String> {
        val out = StringBuilder()
        val outcome = execute(request, caller) { out.append(it) }
        return outcome to out.toString()
    }

    private suspend fun flush(
        batch: List<Record>,
        hydrate: Boolean,
        first: Boolean,
        sink: suspend (String) -> Unit,
    ): Long {
        if (batch.isEmpty()) return 0
        val events =
            if (hydrate && hydrator != null) {
                val ids = LinkedHashSet<String>()
                batch.forEach { r -> r.values().forEach { collectStoredIds(it.asObject(), ids) } }
                if (ids.isEmpty()) emptyMap() else hydrator.fetch(ids.toList()).associateBy { it.id }
            } else {
                null
            }
        val encoder = ResultEncoder(events)
        val text =
            batch.joinToString(",") { r ->
                JSON.encodeToString(JsonElement.serializer(), JsonArray(r.values().map { encoder.encode(it.asObject()) }))
            }
        sink(if (first) text else ",$text")
        return batch.size.toLong()
    }

    private fun collectStoredIds(
        value: Any?,
        into: MutableSet<String>,
    ) {
        when (value) {
            is Node -> if (value.hasLabel(Labels.STORED)) (value.asMap()[Labels.EVENT_KEY] as? String)?.let { into += it }
            is Path -> value.nodes().forEach { collectStoredIds(it, into) }
            is List<*> -> value.forEach { collectStoredIds(it, into) }
            is Map<*, *> -> value.values.forEach { collectStoredIds(it, into) }
        }
    }

    companion object {
        private val JSON = Json

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Graph values → JSON (spec §8.3). `:Event:Stored` nodes become full NIP-01 events when
 * [events] holds them (hydrated), `{"id", "stored": false}` when the source no longer does;
 * other nodes are their properties keyed by label; integers beyond ±2^53 become strings so no
 * JavaScript client silently rounds them.
 */
class ResultEncoder(
    private val events: Map<String, Event>?,
) {
    fun encode(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Node -> node(value)
            is Relationship -> relationship(value)
            is Path -> path(value)
            is List<*> -> JsonArray(value.map { encode(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to encode(it.value) })
            is Long -> number(value)
            is Int -> number(value.toLong())
            is Double -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private fun number(v: Long): JsonElement = if (v in -MAX_SAFE..MAX_SAFE) JsonPrimitive(v) else JsonPrimitive(v.toString())

    private fun props(n: Map<String, Any?>): Map<String, JsonElement> = n.mapValues { encode(it.value) }

    private fun node(n: Node): JsonElement {
        val props = n.asMap()
        return when {
            n.hasLabel(Labels.EVENT) && n.hasLabel(Labels.STORED) -> {
                val id = props[Labels.EVENT_KEY] as String
                when {
                    events == null -> JsonObject(props(props) + ("stored" to JsonPrimitive(true)))
                    events[id] != null -> Json.parseToJsonElement(events[id]!!.toJson())
                    else -> JsonObject(mapOf("id" to JsonPrimitive(id), "stored" to JsonPrimitive(false)))
                }
            }

            n.hasLabel(Labels.EVENT) -> {
                JsonObject(mapOf("id" to encode(props[Labels.EVENT_KEY]), "stored" to JsonPrimitive(false)))
            }

            n.hasLabel(Labels.ADDRESS) -> {
                JsonObject(props(props - Labels.ADDRESS_KEY) + ("address" to encode(props[Labels.ADDRESS_KEY])))
            }

            else -> {
                JsonObject(props(props))
            }
        }
    }

    private fun relationship(r: Relationship): JsonElement =
        JsonObject(
            props(r.asMap()) +
                mapOf(
                    "type" to JsonPrimitive(r.type()),
                    "start" to JsonPrimitive(r.startNodeElementId()),
                    "end" to JsonPrimitive(r.endNodeElementId()),
                ),
        )

    private fun path(p: Path): JsonElement {
        val out = ArrayList<JsonElement>()
        out += node(p.start())
        for (segment in p) {
            out += relationship(segment.relationship())
            out += node(segment.end())
        }
        return JsonArray(out)
    }

    companion object {
        const val MAX_SAFE = 9_007_199_254_740_991L
    }
}
