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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DerivationTest {
    @Test
    fun theStampCarriesTheSchemaMajorTheVersionAndThePolicy() {
        val stamp = Derivation.stamp(GraphPolicy.Default)
        assertEquals(Derivation.major(Derivation.SCHEMA_VERSION)!!.toLong(), stamp ushr 48)
        assertEquals(Derivation.VERSION.toLong(), (stamp ushr 32) and 0xFFFF)
        assertEquals(stamp, Derivation.stamp(GraphPolicy()), "a function of the policy's value")
        assertNotEquals(stamp, Derivation.stamp(GraphPolicy(maxTagValueBytes = 128)), "a changed bound re-derives")
        assertEquals(stamp, Derivation.stamp(GraphPolicy(excludedKinds = setOf(4))), "which kinds are held is not how they derive")
        assertNotEquals(0L, stamp, "0 is what a node written before stamps reads as")
    }

    @Test
    fun majorReadsTheLeadingNumber() {
        assertEquals(2, Derivation.major("2.0"))
        assertEquals(1, Derivation.major("1.7"))
        assertEquals(null, Derivation.major("x.1"))
    }
}
