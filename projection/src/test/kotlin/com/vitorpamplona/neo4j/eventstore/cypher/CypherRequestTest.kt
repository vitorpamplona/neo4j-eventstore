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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CypherRequestTest {
    @Test
    fun parsesQueryParamsAndHydrate() {
        val r =
            CypherRequest.fromJson(
                """{"query":"MATCH (n) RETURN n","params":{"pk":"ab","n":3,"f":1.5,"b":true,"ids":["x","y"],"m":{"k":null}},"hydrate":false}""",
            )
        assertEquals("MATCH (n) RETURN n", r.query)
        assertEquals(
            mapOf("pk" to "ab", "n" to 3L, "f" to 1.5, "b" to true, "ids" to listOf("x", "y"), "m" to mapOf("k" to null)),
            r.params,
        )
        assertEquals(false, r.hydrate)
    }

    @Test
    fun hydrationIsTheDefault() = assertEquals(true, CypherRequest.fromJson("""{"query":"RETURN 1"}""").hydrate)

    @Test
    fun aTypoIsAnErrorNotASilentlyDifferentQuery() {
        assertFailsWith<IllegalArgumentException> { CypherRequest.fromJson("""{"query":"RETURN ${'$'}x","parms":{"x":1}}""") }
    }

    @Test
    fun theQueryIsRequired() {
        assertFailsWith<IllegalArgumentException> { CypherRequest.fromJson("""{"params":{}}""") }
        assertFailsWith<IllegalArgumentException> { CypherRequest.fromJson("""[1]""") }
    }
}
