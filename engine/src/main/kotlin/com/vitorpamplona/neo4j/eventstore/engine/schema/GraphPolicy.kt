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

import java.security.MessageDigest

/**
 * What the projection holds (spec §4.4). Configuration, not schema: its [hash] is recorded in
 * `:Meta`, and a changed policy is converged by the reconciler.
 *
 * - Every kind is projected; [excludedKinds] is an operator knob, EMPTY by default.
 * - A single-letter tag whose value is NOT a reference becomes a `:Tag` node only when its name is
 *   in [tagNodeNames] and its value is at most [maxTagValueBytes]. References are never dropped by
 *   name — every tag that resolves to an event, user or address becomes an edge.
 */
data class GraphPolicy(
    val tagNodeNames: Set<String> = DEFAULT_TAG_NODES,
    val excludedKinds: Set<Int> = emptySet(),
    val maxTagValueBytes: Int = 256,
    val maxCuratedBytes: Int = 256,
) {
    fun admits(kind: Int) = kind !in excludedKinds

    fun isTagNode(
        name: String,
        value: String,
    ) = name in tagNodeNames && value.isNotEmpty() && value.encodeToByteArray().size <= maxTagValueBytes

    fun hash(): String {
        val canonical =
            "tags=" + tagNodeNames.sorted().joinToString(",") +
                ";excluded=" + excludedKinds.sorted().joinToString(",") +
                ";maxTag=" + maxTagValueBytes + ";maxCurated=" + maxCuratedBytes
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    companion object {
        /**
         * Hashtags, NIP-73 external ids, kind references, NIP-32 labels and namespaces, URLs and
         * geohashes: the non-reference tags worth joining on. Not `d` (the `:Address` covers it)
         * and not `x` (one file hash per event would be one useless node per event).
         */
        val DEFAULT_TAG_NODES = setOf("t", "i", "k", "l", "L", "r", "g")

        val Default = GraphPolicy()
    }
}
