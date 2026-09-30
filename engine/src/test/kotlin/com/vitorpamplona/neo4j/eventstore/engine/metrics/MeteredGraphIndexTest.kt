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
package com.vitorpamplona.neo4j.eventstore.engine.metrics

import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.ALICE
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.event
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeteredGraphIndexTest {
    @Test
    fun visitIdsTimesThePageFetchesNotTheCallersWork(): Unit =
        runBlocking {
            val metered = MeteredGraphIndex(InMemoryGraphIndex())
            metered.apply((1..3).map { event(1, ALICE, content = "n$it") })
            metered.visitIds(0, Long.MAX_VALUE, pageSize = 1) { _ ->
                Thread.sleep(200) // the reconciler diffing, fetching, applying
                true
            }
            val (calls, millis, failures) = metered.snapshot().getValue("visitIds")
            assertEquals(1L, calls)
            assertEquals(0L, failures)
            assertTrue(millis < 300, "counted the callback's 600 ms as graph latency: $millis ms")
        }
}
