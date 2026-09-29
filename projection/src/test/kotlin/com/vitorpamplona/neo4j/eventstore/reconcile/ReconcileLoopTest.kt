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
package com.vitorpamplona.neo4j.eventstore.reconcile

import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcileLoopTest {
    @Test
    fun theFullSweepCrossesEmptyDecadesQuicklyAndResumesFromItsCursor() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(8)
            repeat(200) { source.put(corpus.next()) } // created_at ≈ 1.7e9, i.e. late 2023
            val graph = InMemoryGraphIndex()
            val saved = ArrayList<Long>()
            val cursor =
                object : CursorStore {
                    override fun load(): Long? = saved.lastOrNull()

                    override fun save(createdAt: Long) {
                        saved += createdAt
                    }
                }
            val now = 1_700_000_000L + 100_000
            val loop = ReconcileLoop(MirrorReconciler(source, graph), DirtyTracker(), cursor, recentWindowSeconds = 60, nowSecs = { now })

            var ticks = 0
            while (ticks < 5) {
                loop.tick()
                ticks++
                if (loop.sweepCursor == 0L && saved.isNotEmpty()) break
            }
            assertTrue(ticks <= 2, "reaching now from 1970 took $ticks ticks")
            assertTrue(saved.size < 40, "the sweep took ${saved.size} windows to cross 53 years")
            val held = HashSet<String>()
            graph.visitIds(0, Long.MAX_VALUE / 2) { p ->
                p.forEach { held += it.id }
                true
            }
            assertEquals(source.held.keys, held)
        }
}
