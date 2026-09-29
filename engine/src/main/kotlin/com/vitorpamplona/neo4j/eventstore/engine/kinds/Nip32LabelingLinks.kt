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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.LabelProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelNamespaceTag
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag

/** Quartz's `nip32Labeling` classes. */
internal fun KindMappers.Builder.nip32Labeling() {
    // NIP-32: every `e`/`a`/`p`/`t`/`r` is a label TARGET (`LABELED`), so a 1985's `t` and `r` are
    // never its own topics. Each target carries the labels in `labels`: one `<namespace>:<label>`
    // per `l` tag (`ugc` when unmarked, `nip32Qualified`). The `l`/`L` values are `TAG`s. With no
    // target tag the labels apply to the label event itself, which is no link.
    on<LabelEvent> { e ->
        nip32LabelTags(e.tags)

        val props = LabelProps(e.labels().map { it.nip32Qualified() })
        each(e.tags, ETag::parse) { event(Relation.LABELED, it, ETag.TAG_NAME, props) }
        each(e.tags, PTag::parse) { user(Relation.LABELED, it, PTag.TAG_NAME, props) }
        each(e.tags, ATag::parse) { address(Relation.LABELED, it, ATag.TAG_NAME, props) }
        each(e.tags, HashtagTag::parse) { tag(Relation.LABELED, HashtagTag.TAG_NAME, it.lowercase(), props = props) }
        each(e.tags, ReferenceTag::parse) { tag(Relation.LABELED, ReferenceTag.TAG_NAME, it, props = props) }
    }
}

/**
 * The NIP-32 `L` namespaces ([LabelNamespaceTag]) and `l` labels ([LabelTag]) an event carries,
 * each a `TAG` by its value. A 1985 labels its targets with them; a 1984 report classifies itself.
 */
internal fun LinkBuilder.nip32LabelTags(tags: TagArray) {
    each(tags, LabelNamespaceTag::parse) { tag(Relation.TAG, LabelNamespaceTag.TAG_NAME, it.namespace) }
    each(tags, LabelTag::parse) { tag(Relation.TAG, LabelTag.TAG_NAME, it.label) }
}
