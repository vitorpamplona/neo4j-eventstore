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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import com.vitorpamplona.quartz.nip56Reports.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAddressTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedAuthorTag
import com.vitorpamplona.quartz.nip56Reports.tags.ReportedEventTag

/** Quartz's `nip56Reports` classes. */
internal fun KindMappers.Builder.nip56Reports() {
    // NIP-56. What a report is ABOUT decides the relation of its `p` (`Nip56Report.isAboutContent`):
    // when the report names no event, address or blob it is a complaint about the person
    // (`REPORTED_USER`); otherwise the `p` is the reported content's author (`REPORTED_AUTHOR`).
    // The split is by the presence of `e`/`a`/`x`, never by whether the `p` writes its own type:
    // Quartz's own `build` writes the type on both. `e`/`a`/`x` are the `REPORTED` content (`x`: a
    // blob hash).
    //
    // Every one of them carries its category (`nip56Report`): `report`, the category as Quartz
    // reads it (the tag's own type, else the report's default, as a `ReportType` code), and
    // `report_raw`, the type as written (trimmed and lowercased; clients invent types that fold
    // into `other`), else the report's default as written. A blank type slot writes no type.
    on<ReportEvent> { e ->
        val report = e.nip56Report()
        val personRelation = if (report.isAboutContent) Relation.REPORTED_AUTHOR else Relation.REPORTED_USER

        report.events.forEach { event(Relation.REPORTED, it.value, ReportedEventTag.TAG_NAME, it.props) }
        report.authors.forEach { user(personRelation, it.value, ReportedAuthorTag.TAG_NAME, it.props) }
        report.addresses.forEach { address(Relation.REPORTED, it.value, ReportedAddressTag.TAG_NAME, it.props) }
        report.hashes.forEach { tag(Relation.REPORTED, HashSha256Tag.TAG_NAME, it.value, props = it.props) }

        nip32LabelTags(e.tags)
    }
}
