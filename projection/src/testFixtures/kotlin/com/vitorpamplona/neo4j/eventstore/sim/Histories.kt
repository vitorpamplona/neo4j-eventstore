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
package com.vitorpamplona.neo4j.eventstore.sim

import kotlin.random.Random

/** Builds a random source history and replays it as two interleaved, lossy writer feeds. */
object Histories {
    /** Drives [source] with [steps] random operations: puts (winning or stale), removals, resurrections. */
    fun drive(
        source: SimulatedSource,
        corpus: GraphCorpus,
        random: Random,
        steps: Int,
        resurrections: Boolean = true,
    ) {
        repeat(steps) {
            when (random.nextInt(10)) {
                in 0..6 -> {
                    source.put(corpus.next())
                }

                7, 8 -> {
                    source.held.keys
                        .randomOrNull(random)
                        ?.let { source.remove(it) }
                }

                else -> {
                    if (resurrections) source.resurrect { it.randomOrNull(random) } else source.put(corpus.next())
                }
            }
        }
    }

    /**
     * The source's history as it reaches the graph: split across two writer processes (each
     * keeps its own order), interleaved arbitrarily, with some deliveries dropped.
     */
    fun deliveries(
        history: List<SimulatedSource.Change>,
        random: Random,
        dropRate: Double,
    ): Pair<List<SimulatedSource.Change>, List<SimulatedSource.Change>> {
        val a = ArrayDeque<SimulatedSource.Change>()
        val b = ArrayDeque<SimulatedSource.Change>()
        history.forEach { if (random.nextBoolean()) a += it else b += it }
        val delivered = ArrayList<SimulatedSource.Change>()
        val dropped = ArrayList<SimulatedSource.Change>()
        while (a.isNotEmpty() || b.isNotEmpty()) {
            val from = if (b.isEmpty() || (a.isNotEmpty() && random.nextBoolean())) a else b
            val change = from.removeFirst()
            if (random.nextDouble() < dropRate) dropped += change else delivered += change
        }
        return delivered to dropped
    }
}
