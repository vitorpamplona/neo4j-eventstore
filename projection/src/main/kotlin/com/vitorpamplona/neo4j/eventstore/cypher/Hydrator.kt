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

import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * Where full events come from: the graph holds no bodies, so an `:Event:Data` a query returns
 * is fetched from the source of truth by id (spec §8.3). vespa-relay implements it over the
 * Vespa store it already has.
 */
fun interface Hydrator {
    suspend fun fetch(ids: List<String>): List<Event>
}

/** One audit record per call (spec §8.2): enough to reproduce abuse without keeping every query. */
data class CypherAuditEntry(
    val caller: String?,
    val queryHash: String,
    val paramNames: List<String>,
    val elapsedMs: Long,
    val rows: Long,
    val outcome: String,
)

fun interface CypherAudit {
    fun record(entry: CypherAuditEntry)
}
