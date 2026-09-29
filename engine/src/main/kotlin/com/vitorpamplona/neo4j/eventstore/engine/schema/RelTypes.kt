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
 * Relationship type names are the vocabulary's relation names (`vocab/Relation`): plain
 * UPPER_SNAKE Cypher identifiers, part of the public contract (`docs/schema.md`). The source
 * event's kind is a property of the source node, never part of the type: `PARENT` is a reply's
 * parent whatever the reply's kind, and a query that cares filters on `kind`.
 */
object RelTypes {
    private val SAFE = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /**
     * True for a name this module could have produced. The Neo4j binding interpolates type names
     * into Cypher text (types cannot be query parameters in a MATCH pattern the planner can
     * index), so every name is checked against this before it is spliced.
     */
    fun isSafe(type: String) = SAFE.matches(type)
}
