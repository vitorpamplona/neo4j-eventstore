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
 * Relationship type names (spec §4.2) — plain Cypher identifiers, part of the public contract.
 *
 * The SOURCE KIND is in the type on purpose: Neo4j groups a dense node's relationships by type
 * and direction, so `(u)<-[:p_3]-()` walks follow lists only (not a hub's millions of mentions)
 * and `COUNT { (u)<-[:p_3]-() }` is O(1). Kinds outside the [KindRegistry] share an `_other`
 * type carrying a `kind` property, which bounds the type count against spam kinds.
 *
 * The three prefixes cannot collide: a literal tag is ONE letter then `_`; authorship is `by_`;
 * a derived reference is `ref_`. Case is significant (`e_1111` NIP-22 reply vs `E_1111` root).
 */
object RelTypes {
    const val OTHER = "other"
    const val VERSION_OF = "VERSION_OF"
    const val OWNED_BY = "OWNED_BY"

    const val AUTHOR_PREFIX = "by_"
    const val DERIVED_PREFIX = "ref_"

    /** Event → User authorship. */
    fun authored(segment: String) = AUTHOR_PREFIX + segment

    /** Event → target, from the literal single-letter tag [tagName]. */
    fun literal(
        tagName: String,
        segment: String,
    ): String {
        require(tagName.length == 1 && tagName[0].isAsciiLetter()) { "not a single-letter tag: $tagName" }
        return tagName + "_" + segment
    }

    /** Event → target, a link Quartz names that is NOT a literal single-letter tag. [family] is e, p or a. */
    fun derived(
        family: Char,
        segment: String,
    ): String {
        require(family == 'e' || family == 'p' || family == 'a') { "not a reference family: $family" }
        return DERIVED_PREFIX + family + "_" + segment
    }

    private val SAFE = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /**
     * True for a name this module could have produced. The Neo4j binding interpolates type names
     * into Cypher text (types cannot be query parameters in a MATCH pattern the planner can
     * index), so every name is checked against this before it is spliced.
     */
    fun isSafe(type: String) = SAFE.matches(type)

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
