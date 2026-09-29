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

import com.vitorpamplona.neo4j.eventstore.reconcile.SourceOfTruth
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime

/**
 * A stand-in for the store the graph projects: it holds events, applies the ONE rule that
 * matters to the projection (NIP-01 slot supersession, with the lowest-id tiebreak), and records
 * the physical changes it makes — exactly what vespa-eventstore's IndexObserver reports.
 *
 * [reportSupersessionRemovals] = false models Vespa's address-keyed path, where the loser's
 * removal happens inside the engine and is never reported.
 */
class SimulatedSource(
    private val reportSupersessionRemovals: Boolean = true,
) : SourceOfTruth {
    sealed interface Change {
        data class Put(
            val event: Event,
        ) : Change

        data class Remove(
            val id: String,
        ) : Change
    }

    val held = LinkedHashMap<String, Event>()
    val history = ArrayList<Change>()
    private val everRemoved = LinkedHashMap<String, Event>()

    private fun slotOf(e: Event): String? =
        when {
            e.kind.isReplaceable() -> "${e.kind}:${e.pubKey}"
            e.kind.isAddressable() -> "${e.kind}:${e.pubKey}:" + (e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "")
            else -> null
        }

    /** Stores [e] if it wins its slot; returns whether it was stored. */
    fun put(e: Event): Boolean {
        if (e.id in held) return false
        val slot = slotOf(e)
        if (slot != null) {
            val incumbent = held.values.firstOrNull { slotOf(it) == slot }
            if (incumbent != null) {
                val newer = e.createdAt > incumbent.createdAt || (e.createdAt == incumbent.createdAt && e.id < incumbent.id)
                if (!newer) return false
                held.remove(incumbent.id)
                everRemoved[incumbent.id] = incumbent
                if (reportSupersessionRemovals) history += Change.Remove(incumbent.id)
            }
        }
        held[e.id] = e
        history += Change.Put(e)
        return true
    }

    /** A deletion, vanish, expiry or sweep: the event is gone. */
    fun remove(id: String) {
        val e = held.remove(id) ?: return
        everRemoved[id] = e
        history += Change.Remove(id)
    }

    /** A previously removed event comes back (an orphan-swept 30382 re-mirrored, say). */
    fun resurrect(pick: (List<Event>) -> Event?): Boolean {
        val candidate = pick(everRemoved.values.filter { it.id !in held }) ?: return false
        return put(candidate)
    }

    override suspend fun visitIds(
        since: Long,
        until: Long,
        onPage: suspend (List<IdAndTime>) -> Boolean,
    ) {
        val ids =
            held.values
                .filter { it.createdAt in since..until }
                .map { IdAndTime(it.createdAt, it.id) }
                .sortedWith(compareBy<IdAndTime> { it.createdAt }.thenBy { it.id })
        for (page in ids.chunked(37)) if (!onPage(page)) return
    }

    override suspend fun fetch(ids: List<String>): List<Event> = ids.mapNotNull { held[it] }
}
