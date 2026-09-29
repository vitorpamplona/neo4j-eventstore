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

import com.vitorpamplona.quartz.utils.EventFactory
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Which kinds get their OWN relationship types (`p_3`) and which share `_other` (spec §4.2).
 *
 * The default is every kind the pinned Quartz types ([EventFactory.isKnownKind]) — a few hundred,
 * bounded, and exactly the kinds whose tags the hint providers understand. A Quartz bump that
 * learns a kind changes [version]; `KindRegistryMigration` then moves that kind's `_other` edges.
 */
class KindRegistry(
    val version: String,
    private val isKnown: (Int) -> Boolean,
) {
    // Memoized: isKnownKind builds a throwaway event per call, and this sits on the write path.
    private val cache = AtomicReferenceArray<Boolean?>(MAX_KIND + 1)

    fun isRegistered(kind: Int): Boolean {
        if (kind < 0 || kind > MAX_KIND) return false
        cache.get(kind)?.let { return it }
        val known = isKnown(kind)
        cache.set(kind, known)
        return known
    }

    /** The kind segment of a relationship type: the kind itself, or [RelTypes.OTHER]. */
    fun segment(kind: Int): String = if (isRegistered(kind)) kind.toString() else RelTypes.OTHER

    companion object {
        const val MAX_KIND = 65_535

        /** Every kind Quartz's [EventFactory] types, at the pinned Quartz. */
        fun quartzKnownKinds(version: String = "quartz-known-kinds-v1") = KindRegistry(version) { EventFactory.isKnownKind(it) }
    }
}
