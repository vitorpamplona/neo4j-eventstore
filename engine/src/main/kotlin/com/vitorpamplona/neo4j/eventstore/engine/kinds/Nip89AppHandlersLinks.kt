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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.PlatformProps
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip89AppHandlers.definition.AppDefinitionEvent
import com.vitorpamplona.quartz.nip89AppHandlers.recommendation.AppRecommendationEvent
import com.vitorpamplona.quartz.nip89AppHandlers.recommendation.tags.RecommendationTag

/** Quartz's `nip89AppHandlers` classes. The `client` tag any kind carries is [everyKindLinks]'. */
internal fun KindMappers.Builder.nip89AppHandlers() {
    /*
     * NIP-89: "App descriptor events SHOULD tag or otherwise reference related site manifest
     * events"; a [Nip89ManifestReleaseTag] (`latest`, `next`) says which release a manifest is, a
     * plain `a` does not. The `client` tag is linked for every kind by [KindLinks.of].
     */
    on<AppDefinitionEvent> { e ->
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
        each(e.tags, Nip89ManifestReleaseTag::parse) { address(Relation.SITE_MANIFEST, it.address, it.release, it.linkProps()) }
        each(e.tags, ATag::parse) { address(Relation.SITE_MANIFEST, it, ATag.TAG_NAME) }
        hashtags(e.tags)
    }
    // NIP-89: each `a` to a 31990 handler is a recommendation; its 4th slot says for which platform
    // (a blank one names none). An `a` to any other kind recommends nothing NIP-89 defines, so it is not linked.
    on<AppRecommendationEvent> { e ->
        each(e.tags, RecommendationTag::parse) {
            if (it.address.kind == AppDefinitionEvent.KIND) {
                address(Relation.RECOMMENDED, it.address, RecommendationTag.TAG_NAME, PlatformProps(it.platform?.ifBlank { null }))
            }
        }
    }
}
