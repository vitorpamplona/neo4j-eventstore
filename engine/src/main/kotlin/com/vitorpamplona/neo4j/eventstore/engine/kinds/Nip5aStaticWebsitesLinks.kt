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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.NoProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent

/** Quartz's `nip5aStaticWebsites` classes. */
internal fun KindMappers.Builder.nip5aStaticWebsites() {
    on<NamedSiteEvent> { e -> nip5aSiteManifestLinks(e.tags) }
    on<RootSiteEvent> { e -> nip5aSiteManifestLinks(e.tags) }
}

/**
 * The references an nsite manifest carries (NIP-5A, and the NIP-5D napplets that reuse its tag
 * set): [Nip5aAppTag] is "an addressable event reference to an app descriptor" ([Relation.APP]),
 * and the copy lineage is a lowercase `a` ([ATag]) to "the immediate parent nsite from which it
 * was copied" ([Relation.COPIED]) plus an uppercase `A` ([Nip5aOriginTag]) to "the origin nsite
 * of the copy lineage" ([Relation.ORIGIN]).
 * [parent] names the lowercase `a`: a manifest snapshot's single `a` is instead the root or
 * named site it snapshots ([Relation.SNAPSHOTTED]). `path`, `x`, `server` and `source` are
 * blob hashes and URLs, not links.
 */
internal fun LinkBuilder.nip5aSiteManifestLinks(
    tags: TagArray,
    parent: Relation<NoProps> = Relation.COPIED,
) {
    each(tags, ATag::parse) { address(parent, it, ATag.TAG_NAME) }
    each(tags, Nip5aOriginTag::parse) { address(Relation.ORIGIN, it, Nip5aOriginTag.TAG_NAME) }
    each(tags, Nip5aAppTag::parse) { address(Relation.APP, it, Nip5aAppTag.TAG_NAME) }
}
