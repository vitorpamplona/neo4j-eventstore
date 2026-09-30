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

/**
 * What a held event's projection was DERIVED with, so a graph can tell which of its events an
 * older build wrote and re-derive them (spec §7.2). An event already `:Stored` is never re-applied
 * — a put of it is a duplicate, and the reconciler diffs id sets — so without this a mapper fix,
 * a Quartz bump that changes a parse, or a new extractor would reach only events applied after it
 * ships.
 *
 * Every `:Stored` node carries the [stamp] it was written with (property [PROPERTY]); `:Meta`
 * records the stamp the graph is being converged to. The reconciler re-derives, in place, every
 * held event whose stamp differs from the running build's, paced by the full sweep's budget.
 */
object Derivation {
    /**
     * The public schema version (spec §8.6): minor for additive changes, major for renames and
     * removals. 2.0: relationship types are the vocabulary's relations (`PARENT`, `FOLLOW`,
     * `REPORTED_USER`, `docs/vocabulary.md`), no longer `<tag>_<kind>`. A graph built under
     * another MAJOR is refused (`SchemaInstaller`): re-deriving cannot migrate what a query
     * already relies on, so a major change is a rebuild.
     */
    const val SCHEMA_VERSION = "2.0"

    /**
     * BUMP BY HAND whenever the mappers (`kinds/`), the vocabulary or the derivation (`derive/`)
     * change what an existing event projects to: a relation re-mapped, a prop added, a Quartz pin
     * whose parsers read a tag differently, a new curated value. The bump is what makes the
     * reconciler rewrite the events already held; forgetting it leaves them as the old code wrote
     * them until a rebuild. A change that only affects kinds no stored event has (a new kind's
     * mapper) does not need it, but bumping is never wrong — it only costs a paced re-derive.
     */
    const val VERSION: Int = 1

    /** The `:Stored` node property (and the `:Meta` one) holding a [stamp]. */
    const val PROPERTY = "derived"

    /** The schema MAJOR of a version string (`"2.0"` → 2); null when it is not one. */
    fun major(version: String): Int? = version.substringBefore('.').toIntOrNull()

    /**
     * The derivation stamp for [policy]: the schema major (bits 48–63), [VERSION] (bits 32–47)
     * and the first 32 bits of the policy hash (bits 0–31) in one Long — the policy bounds what
     * derivation keeps (tag values, curated text), so a changed bound re-derives too. One Long per
     * node is 8 bytes of property store; a node without the property (a graph written before
     * stamps) reads as 0, which no build stamps, so it is re-derived as well.
     */
    fun stamp(policy: GraphPolicy): Long {
        val major = major(SCHEMA_VERSION) ?: 0
        val policyBits = policy.hash().take(8).toLong(16)
        return (major.toLong() and 0xFFFF shl 48) or (VERSION.toLong() and 0xFFFF shl 32) or policyBits
    }
}
