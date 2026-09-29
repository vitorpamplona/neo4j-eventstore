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
package com.vitorpamplona.neo4j.eventstore.engine.schema

/**
 * Node labels and their key properties — part of the PUBLIC query contract (docs/schema.md):
 * strangers write Cypher against these names, so a rename is a major schema version.
 */
object Labels {
    /** Every event node, held or not. Key: [EVENT_KEY]. */
    const val EVENT = "Event"

    /**
     * Added to an [EVENT] the source of truth currently holds. A node without it is a STUB: an id
     * something references but the projection does not hold (never seen, or removed) — kept so
     * the reference survives and a later arrival lands on it.
     */
    const val STORED = "Stored"

    const val USER = "User"
    const val ADDRESS = "Address"
    const val TAG = "Tag"

    /** Singleton bookkeeping node: schema version, kind-registry version, policy hash. */
    const val META = "Meta"

    /**
     * The recent-removal fence (spec §6.2): one node per id unapplied in the last
     * [com.vitorpamplona.neo4j.eventstore.engine.GraphIndex] fence window, so a put delivered AFTER its own
     * removal (two writer processes, two queues) is not resurrected.
     */
    const val REMOVED = "Removed"

    const val EVENT_KEY = "id"
    const val USER_KEY = "pubkey"
    const val ADDRESS_KEY = "id"
    const val TAG_KEY = "key"
}
