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
 * - Which tag values become `:Tag` nodes is not configuration: each kind's mapper decides
 *   (`docs/vocabulary.md`, rule 5). A value over [maxTagValueBytes] never becomes a node.
 * - Curated text lifted onto nodes (names, titles) is cut to [maxCuratedBytes].
 */
data class GraphPolicy(
    val excludedKinds: Set<Int> = emptySet(),
    val maxTagValueBytes: Int = 256,
    val maxCuratedBytes: Int = 256,
) {
    fun admits(kind: Int) = kind !in excludedKinds

    /** Whether [value] is small enough to key a `:Tag` node. */
    fun fitsTagNode(value: String): Boolean {
        if (value.isEmpty()) return false
        // Every :Tag link passes here: skip the byte copy when even 3 bytes a char fits.
        if (value.length * 3 <= maxTagValueBytes) return true
        return value.length <= maxTagValueBytes && value.encodeToByteArray().size <= maxTagValueBytes
    }

    /**
     * The part of the policy that changes what a HELD event projects to: the bounds on tag values
     * and curated text. [excludedKinds] is left out on purpose: it decides which events are held
     * (the reconciler converges that by adding and removing them), not how a held one derives, so
     * changing it must not re-derive the whole graph.
     */
    fun derivationHash(): String = sha16("maxTag=" + maxTagValueBytes + ";maxCurated=" + maxCuratedBytes)

    fun hash(): String =
        sha16(
            "excluded=" + excludedKinds.sorted().joinToString(",") +
                ";maxTag=" + maxTagValueBytes + ";maxCurated=" + maxCuratedBytes,
        )

    private fun sha16(canonical: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    companion object {
        val Default = GraphPolicy()
    }
}
