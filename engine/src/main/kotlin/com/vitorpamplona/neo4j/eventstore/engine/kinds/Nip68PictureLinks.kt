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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.PositionProps
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip68Picture.userAnnotations
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.nip92IMeta.imetas

/** Quartz's `nip68Picture` classes. */
internal fun KindMappers.Builder.nip68Picture() {
    // NIP-68 names its `p` tags "tagged users", and an imeta `annotate-user` places one at a point in the image (its position rides on the link).
    on<PictureEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.TAGGED, it, PTag.TAG_NAME) }
        // Only the annotations are read: `imetaTags()` would build a whole PictureMeta per image
        // (dimensions, hashes, fallbacks…) to throw it away.
        e.imetas().forEach { image ->
            image.userAnnotations()?.forEach { user(Relation.TAGGED, it.pubkey, IMetaTag.TAG_NAME, PositionProps(it.x, it.y)) }
        }
        hashtags(e.tags)
        each(e.tags, GeoHashTag::parse) { value(Relation.LOCATION, ValueType.GEOHASH, it, GeoHashTag.TAG_NAME) }
    }
}
