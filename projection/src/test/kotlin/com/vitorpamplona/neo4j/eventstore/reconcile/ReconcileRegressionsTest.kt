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

import com.vitorpamplona.neo4j.eventstore.engine.ApplyOutcome
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.SIG
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.hex
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reconcile shapes the audit of 2026-09-29 found wrong, one test each. */
class ReconcileRegressionsTest {
    private suspend fun GraphIndex.heldIds(): Set<String> {
        val out = HashSet<String>()
        visitIds(Long.MIN_VALUE, Long.MAX_VALUE) { p ->
            p.forEach { out += it.id }
            true
        }
        return out
    }

    private fun note(
        seed: String,
        createdAt: Long,
    ) = Event(hex(seed), hex("author"), createdAt, 1, emptyArray(), seed, SIG)

    @Test
    fun aWideWindowHoldingTheCorpusStopsAtTheTickBudget() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(21)
            repeat(2_000) { source.put(corpus.next()) }
            val graph = InMemoryGraphIndex()
            val now = 1_700_000_000L + 100_000
            val loop =
                ReconcileLoop(
                    MirrorReconciler(source, graph, maxWindowIds = 100),
                    DirtyTracker(),
                    recentWindowSeconds = 0,
                    sweepIdsPerTick = 300,
                    nowSecs = { now },
                )
            loop.tick()
            val afterOne = graph.heldIds().size
            assertTrue(afterOne in 1..500, "one tick reconciled $afterOne of ${source.held.size} ids, past its budget")
            var ticks = 1
            while (graph.heldIds() != source.held.keys && ticks < 50) {
                loop.tick()
                ticks++
            }
            assertEquals(source.held.keys, graph.heldIds(), "the sweep resumes where the budget stopped it")
        }

    @Test
    fun theSweepCoversTheFarFutureAndBeforeTheEpoch() =
        runTest {
            val source = SimulatedSource()
            val future = note("year 2100", 4_102_444_800L)
            val past = note("before 1970", -1_000L)
            source.put(future)
            source.put(past)
            val graph = InMemoryGraphIndex()
            val loop =
                ReconcileLoop(MirrorReconciler(source, graph), DirtyTracker(), recentWindowSeconds = 60, nowSecs = { 1_700_000_000L })
            repeat(3) { loop.tick() }
            assertEquals(setOf(future.id, past.id), graph.heldIds())
        }

    @Test
    fun anEventTheFeedAppliesDuringTheDiffIsNotUnapplied() =
        runTest {
            val inner = SimulatedSource()
            val graph = InMemoryGraphIndex()
            val late = note("arrives mid-diff", 1_700_000_050L)
            // Right after the source is listed, the feed stores (and applies) a new event: in the
            // old source-then-graph order the graph listing then held an id the source listing
            // lacked, and the event was unapplied as EXTRA.
            val racing =
                object : SourceOfTruth by inner {
                    override suspend fun visitIds(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<IdAndTime>) -> Boolean,
                    ) {
                        inner.visitIds(since, until, onPage)
                        if (inner.put(late)) graph.apply(listOf(late))
                    }
                }
            val report = MirrorReconciler(racing, graph).reconcile(1_700_000_000L, 1_700_000_100L)
            assertEquals(0L, report.extra)
            assertEquals(setOf(late.id), graph.heldIds())
        }

    // Was "a failing tick puts back the dirty hours it did not reach": the tick THREW at the first
    // failing hour, so one poison window blocked every later hour and every other stage, tick
    // after tick. Now the hour is put back with backoff and the rest go on.
    @Test
    fun aDirtyHourThatThrowsIsPutBackWithBackoffAndTheRestGoOn() =
        runTest {
            val failingHour = 7_200L
            val source =
                object : SourceOfTruth by SimulatedSource() {
                    override suspend fun visitIds(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<IdAndTime>) -> Boolean,
                    ) {
                        if (since == failingHour) error("vespa timed out")
                    }
                }
            val dirty = DirtyTracker()
            listOf(0L, 3_600L, 7_200L, 10_800L).forEach { dirty.markCreatedAt(it) }
            var now = 1_700_000_000L
            val errors = ArrayList<Throwable>()
            val loop = ReconcileLoop(MirrorReconciler(source, InMemoryGraphIndex()), dirty, nowSecs = { now })
            val report = loop.tick { errors += it }
            assertEquals(1L, report.errors)
            assertEquals(1, errors.size)
            assertEquals(1, dirty.pendingHours(), "only the failing hour is still owed")
            assertEquals(emptyList(), dirty.drainHours(now), "not retried before its backoff")
            now += DirtyTracker.BACKOFF_BASE_SECONDS
            assertEquals(listOf(failingHour), dirty.drainHours(now))
        }

    @Test
    fun aCancelledTickStillPutsBackTheDirtyHoursItDidNotReach() =
        runTest {
            val source =
                object : SourceOfTruth by SimulatedSource() {
                    override suspend fun visitIds(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<IdAndTime>) -> Boolean,
                    ) = awaitCancellation()
                }
            val dirty = DirtyTracker()
            listOf(0L, 3_600L).forEach { dirty.markCreatedAt(it) }
            val loop = ReconcileLoop(MirrorReconciler(source, InMemoryGraphIndex()), dirty)
            loop.start(backgroundScope)
            runCurrent()
            assertEquals(0, dirty.pendingHours(), "drained by the running tick")
            loop.stop()
            assertEquals(listOf(0L, 3_600L), dirty.drainHours())
        }

    @Test
    fun theTickSweepsTheFence() =
        runTest {
            var now = 1_700_000_000L
            val graph = InMemoryGraphIndex(nowSecs = { now })
            val e = note("removed once", now)
            graph.unapply(listOf(e.id))
            assertEquals(1, graph.apply(listOf(e)).fenced)
            now += 3 * 3_600
            ReconcileLoop(MirrorReconciler(SimulatedSource(), graph), DirtyTracker(), nowSecs = { now }).tick()
            // Past the fence window it would apply either way; what the sweep removes is the entry.
            val fenceBefore = InMemoryGraphIndex(nowSecs = { now }, fenceSeconds = Long.MAX_VALUE / 4)
            fenceBefore.unapply(listOf(e.id))
            ReconcileLoop(
                MirrorReconciler(SimulatedSource(), fenceBefore),
                DirtyTracker(),
                fenceRetentionSeconds = 0,
                nowSecs = { now + 10 },
            ).tick()
            assertEquals(1, fenceBefore.apply(listOf(e)).applied, "a swept fence entry no longer blocks")
        }

    @Test
    fun dirtyWorkSurvivesARestart() {
        val file = File.createTempFile("dirty", ".txt").apply { deleteOnExit() }
        val a = DirtyTracker()
        a.markCreatedAt(1_700_000_123L)
        a.markRemovals(listOf("x".repeat(64)))
        a.save(file)
        val b = DirtyTracker()
        b.load(file)
        assertEquals(listOf(1_699_999_200L), b.drainHours())
        assertEquals(listOf("x".repeat(64)), b.drainRemovals())
    }

    private fun addressable(
        seed: String,
        createdAt: Long,
        author: String = hex("author"),
    ) = Event(hex(seed), author, createdAt, 30023, arrayOf(arrayOf("d", "doc"), arrayOf("title", seed)), "", SIG)

    /** A source whose [fetch] answers, then lets [afterFetch] change the world before the caller acts on the answer. */
    private class RacingSource(
        val inner: SimulatedSource,
        var afterFetch: suspend () -> Unit = {},
    ) : SourceOfTruth by inner {
        override suspend fun fetch(ids: List<String>): List<Event> =
            inner.fetch(ids).also {
                val hook = afterFetch
                afterFetch = {}
                hook()
            }
    }

    @Test
    fun aReconcileDoesNotResurrectAnEventTheFeedRemovesAfterTheFetch() =
        runTest {
            var now = 1_700_000_000L
            val graph = InMemoryGraphIndex(nowSecs = { now })
            val e = note("deleted mid-reconcile", 1_700_000_010L)
            val source = RacingSource(SimulatedSource().apply { put(e) })
            // The fetch says held; right after, the source deletes it and the feed unapplies it.
            source.afterFetch = {
                source.inner.remove(e.id)
                graph.unapply(listOf(e.id))
            }
            MirrorReconciler(source, graph, nowSecs = { now }).reconcile(1_700_000_000L, 1_700_000_100L)
            assertEquals(emptySet(), graph.heldIds(), "the fence is newer than the fetch: it wins")
        }

    @Test
    fun aReconcileDoesNotEvictAVersionTheSourceSupersededWithAfterTheFetch() =
        runTest {
            val graph = InMemoryGraphIndex()
            val older = addressable("v1", 1_700_000_010L)
            val newer = addressable("v2", 1_700_000_020L)
            val source = RacingSource(SimulatedSource().apply { put(older) })
            // The fetch returns v1; before it is applied, the source stores v2 (superseding v1)
            // and the feed applies it.
            source.afterFetch = {
                source.inner.put(newer)
                graph.apply(listOf(newer))
            }
            MirrorReconciler(source, graph).reconcile(1_700_000_000L, 1_700_000_015L)
            assertEquals(setOf(newer.id), graph.heldIds(), "the newer version the source holds keeps its slot")
        }

    @Test
    fun anOutrankingVersionTheSourceNoLongerHoldsIsRemovedAndTheHeldOneApplied() =
        runTest {
            // The graph kept v2 (its removal was dropped); the source holds v1 again. v2 sits in
            // another window, so only the slot contest can find it.
            val graph = InMemoryGraphIndex()
            val v1 = addressable("v1", 1_700_000_010L)
            val v2 = addressable("v2", 1_700_000_900L)
            graph.apply(listOf(v2))
            val source = SimulatedSource().apply { put(v1) }
            val report = MirrorReconciler(source, graph).reconcile(1_700_000_000L, 1_700_000_100L)
            assertEquals(1L, report.missing)
            assertEquals(1L, report.extra)
            assertEquals(setOf(v1.id), graph.heldIds())
            assertEquals(InMemoryGraphIndex().apply { apply(listOf(v1)) }.dump(), graph.dump())
        }

    @Test
    fun aHeldEventAnOlderDerivationWroteIsReDerivedByAReconcile() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(31)
            repeat(200) { source.put(corpus.next()) }
            // An older build: it bounded tag values at 4 bytes, so it linked fewer tags.
            val old = InMemoryGraphIndex(EdgeDeriver(GraphPolicy(maxTagValueBytes = 4)))
            old.apply(source.held.values.toList())
            val graph = old.reopen(EdgeDeriver())
            val fresh = InMemoryGraphIndex().apply { apply(source.held.values.toList()) }.dump()
            assertTrue(graph.dump() != fresh, "the older derivation projects differently")

            val report = MirrorReconciler(source, graph).reconcile(Long.MIN_VALUE, Long.MAX_VALUE)
            assertEquals(source.held.size.toLong(), report.rederived)
            assertEquals(0L, report.missing + report.extra)
            assertEquals(fresh, graph.dump())
            assertEquals(0L, MirrorReconciler(source, graph).reconcile(Long.MIN_VALUE, Long.MAX_VALUE).rederived, "done once")
        }

    @Test
    fun reDerivationIsPacedByTheSweepBudget() =
        runTest {
            val source = SimulatedSource()
            val corpus = GraphCorpus(32)
            repeat(600) { source.put(corpus.next()) }
            val old = InMemoryGraphIndex(EdgeDeriver(GraphPolicy(maxTagValueBytes = 4)))
            old.apply(source.held.values.toList())
            val graph = old.reopen(EdgeDeriver())
            val now = 1_700_000_000L + 100_000
            val loop =
                ReconcileLoop(
                    MirrorReconciler(source, graph),
                    DirtyTracker(),
                    recentWindowSeconds = 0,
                    sweepIdsPerTick = 500,
                    nowSecs = { now },
                )
            val first = loop.tick()
            assertTrue(first.rederived in 1..500, "one tick re-derived ${first.rederived} of ${source.held.size}: past its budget")
            var total = first.rederived
            var ticks = 1
            while (total < source.held.size && ticks < 20) {
                total += loop.tick().rederived
                ticks++
            }
            assertEquals(source.held.size.toLong(), total, "the sweep resumes where the budget stopped it")
            assertEquals(InMemoryGraphIndex().apply { apply(source.held.values.toList()) }.dump(), graph.dump())
        }

    @Test
    fun eventsTheGraphRefusesInAReconcileAreOwedWithBackoff() =
        runTest {
            val e = note("poison", 1_700_000_010L)
            val source = SimulatedSource().apply { put(e) }
            val refusing =
                object : GraphIndex by InMemoryGraphIndex() {
                    override suspend fun apply(
                        events: List<Event>,
                        authoritativeAsOf: Long?,
                    ) = ApplyOutcome(failed = events)
                }
            val dirty = DirtyTracker()
            var now = 1_700_000_000L + 1_000
            val loop = ReconcileLoop(MirrorReconciler(source, refusing), dirty, recentWindowSeconds = 3_600, nowSecs = { now })
            val report = loop.tick()
            assertTrue(report.failed >= 1)
            assertEquals(1, dirty.pendingHours(), "the refused event's hour is owed")
            assertEquals(emptyList(), dirty.drainHours(now), "but not before its backoff")
            now += DirtyTracker.BACKOFF_BASE_SECONDS
            assertEquals(listOf(DirtyTracker.hourOf(e.createdAt)), dirty.drainHours(now))
            // A second consecutive failure doubles the wait.
            dirty.markFailing(e.createdAt, now)
            assertEquals(emptyList(), dirty.drainHours(now + DirtyTracker.BACKOFF_BASE_SECONDS))
            assertEquals(1, dirty.drainHours(now + 2 * DirtyTracker.BACKOFF_BASE_SECONDS).size)
        }

    @Test
    fun removalsKeepWhatFitsAndTheOverflowClearsOnceAWholeSweepHasRun() =
        runTest {
            val dirty = DirtyTracker(maxRemovals = 3)
            dirty.markRemovals(listOf("a", "b"))
            dirty.markRemovals(listOf("c", "d", "e"))
            assertEquals(3, dirty.pendingRemovals(), "what fits is kept")
            assertTrue(dirty.overflowed)

            val loop = ReconcileLoop(MirrorReconciler(SimulatedSource(), InMemoryGraphIndex()), dirty, nowSecs = { 1_700_000_000L })
            loop.tick() // the pass that starts here began after the overflow...
            var ticks = 1
            while (dirty.overflowed && ticks < 10) {
                loop.tick()
                ticks++
            }
            assertTrue(!dirty.overflowed, "...and clears it when it wraps")

            // An overflow DURING a pass is not cleared by that pass's wrap.
            val mid = DirtyTracker(maxRemovals = 1)
            val midLoop =
                ReconcileLoop(
                    MirrorReconciler(SimulatedSource(), InMemoryGraphIndex()),
                    mid,
                    sweepIdsPerTick = 1,
                    nowSecs = { 1_700_000_000L },
                )
            midLoop.tick()
            mid.markRemovals(listOf("x", "y"))
            while (midLoop.sweepCursor != 0L) midLoop.tick()
            assertTrue(mid.overflowed, "a pass that began before the overflow cannot vouch for it")
        }

    @Test
    fun excludedKindsListedWithTheirKindAreNeverFetched() =
        runTest {
            val inner = SimulatedSource()
            val keep = note("kept", 1_700_000_010L)
            val excluded = Event(hex("excluded"), hex("author"), 1_700_000_020L, 7, arrayOf(arrayOf("e", keep.id)), "+", SIG)
            inner.put(keep)
            inner.put(excluded)
            val fetched = ArrayList<String>()
            val source =
                object : SourceOfTruth by inner {
                    override suspend fun visitRefs(
                        since: Long,
                        until: Long,
                        onPage: suspend (List<SourceRef>) -> Boolean,
                    ) {
                        onPage(
                            inner.held.values
                                .filter { it.createdAt in since..until }
                                .map { SourceRef(it.createdAt, it.id, it.kind) },
                        )
                    }

                    override suspend fun fetch(ids: List<String>): List<Event> {
                        fetched += ids
                        return inner.fetch(ids)
                    }
                }
            val policy = GraphPolicy(excludedKinds = setOf(7))
            val graph = InMemoryGraphIndex(EdgeDeriver(policy))
            MirrorReconciler(source, graph, policy).reconcile(1_700_000_000L, 1_700_000_100L)
            assertEquals(setOf(keep.id), graph.heldIds())
            assertEquals(listOf(keep.id), fetched, "the excluded kind's body is never read")
        }
}
