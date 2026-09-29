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
package com.vitorpamplona.neo4j.eventstore.engine.kinds

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rule 5 of the vocabulary (`docs/vocabulary.md`): every class the pinned Quartz's [EventFactory]
 * builds has a mapper that says what its references mean, or is registered link-free. There is no
 * fallback, so a Quartz bump that adds a kind fails here until the kind is decided. The failure
 * lists the undecided classes by package.
 */
class KindMappersCoverageTest {
    @Test
    fun everyEventClassHasAMapper() {
        // The factory picks a class by kind, and for kind 20001 also by the presence of an `h`
        // (NIP-29) or `g` (geohash) tag, so probe each kind with each of those shapes too.
        val probes = listOf(emptyArray(), arrayOf(arrayOf("h", "group")), arrayOf(arrayOf("g", "u4pr")))
        val undecided = sortedSetOf<String>()
        var classes = 0
        val seen = HashSet<Class<*>>()
        for (kind in 0..65535) {
            for (tags in probes) {
                val event = EventFactory.create<Event>("", "", 0L, kind, tags, "", "")
                if (event.javaClass == Event::class.java) continue
                if (!seen.add(event.javaClass)) continue
                classes++
                if (KindLinks.mappers.mapperFor(event.javaClass) == null) {
                    undecided += "${event.javaClass.name.removePrefix("com.vitorpamplona.quartz.")} ($kind)"
                }
            }
        }
        assertTrue(classes > 400, "the probe found only $classes classes")
        assertTrue(undecided.isEmpty(), "${undecided.size} of $classes classes have no mapper:\n" + undecided.joinToString("\n"))
    }

    /** A mapper registered for a class the factory never builds is dead code, or a typo'd base. */
    @Test
    fun everyRegisteredClassIsAnEventClass() {
        KindLinks.mappers.registered.forEach { assertTrue(Event::class.java.isAssignableFrom(it), "${it.name} is not an Event") }
    }
}
