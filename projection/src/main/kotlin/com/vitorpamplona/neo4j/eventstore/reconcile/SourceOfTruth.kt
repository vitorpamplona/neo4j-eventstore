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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime

/**
 * The store the graph projects (vespa-eventstore in vespa-relay), seen through the two reads
 * reconciliation and hydration need (spec §7.1). Reads must be RAW — un-lensed, un-gated,
 * including events a trust floor or an expiry filter would hide from a relay client — because
 * the projection mirrors what is STORED, not what a client would be served.
 */
interface SourceOfTruth {
    /** Every stored event with `created_at` in `[since, until]` as (created_at, id), in pages. [onPage] returns whether to continue. */
    suspend fun visitIds(
        since: Long,
        until: Long,
        onPage: suspend (List<IdAndTime>) -> Boolean,
    )

    /**
     * [visitIds] with each event's kind, for a source that can list it without reading bodies.
     * Only a policy that EXCLUDES kinds needs it: the reconciler leaves an excluded kind's id out
     * of its diff by its [SourceRef.kind], where it would otherwise have to fetch every body in
     * the window to learn it. The default answers [visitIds] with the kind unknown (null), so an
     * implementation that predates this member keeps working, at that cost.
     */
    suspend fun visitRefs(
        since: Long,
        until: Long,
        onPage: suspend (List<SourceRef>) -> Boolean,
    ) = visitIds(since, until) { page -> onPage(page.map { SourceRef(it.createdAt, it.id) }) }

    /** The stored events among [ids] (absent ids are simply missing from the answer). */
    suspend fun fetch(ids: List<String>): List<Event>
}

/** One entry of a [SourceOfTruth.visitRefs] listing; [kind] is null when the source cannot say it cheaply. */
data class SourceRef(
    val createdAt: Long,
    val id: String,
    val kind: Int? = null,
)
