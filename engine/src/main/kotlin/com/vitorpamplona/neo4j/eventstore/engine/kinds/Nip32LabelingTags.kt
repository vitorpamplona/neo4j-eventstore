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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag
import com.vitorpamplona.quartz.utils.ensure

/** `<namespace>:<label>`: one label, told apart from the same word in another namespace. Main's [LabelTag] has no `qualified()`. */
internal fun LabelTag.nip32Qualified() = "$namespace:$label"

/** A NIP-32 label target: what one of a 1985's `e`/`p`/`a`/`t`/`r` tags names. */
internal sealed interface Nip32LabelTarget {
    data class OfEvent(
        val tag: ETag,
    ) : Nip32LabelTarget

    data class OfUser(
        val tag: PTag,
    ) : Nip32LabelTarget

    data class OfAddress(
        val tag: ATag,
    ) : Nip32LabelTarget

    /** A `t` (lowercased, as every hashtag is) or an `r`: [name] is the tag, [type] what its value is. */
    data class OfTag(
        val name: String,
        val type: ValueType,
        val value: String,
    ) : Nip32LabelTarget
}

/**
 * Every NIP-32 target tag in one read: the tag's name picks the one Quartz parser that can
 * accept it, so a 1985 walks its tags once for its five target kinds instead of once per kind.
 */
internal object Nip32LabelTargetTag {
    fun parse(tag: Array<String>): Nip32LabelTarget? {
        ensure(tag.has(1)) { return null }
        return when (tag[0]) {
            ETag.TAG_NAME -> {
                ETag.parse(tag)?.let { Nip32LabelTarget.OfEvent(it) }
            }

            PTag.TAG_NAME -> {
                PTag.parse(tag)?.let { Nip32LabelTarget.OfUser(it) }
            }

            ATag.TAG_NAME -> {
                ATag.parse(tag)?.let { Nip32LabelTarget.OfAddress(it) }
            }

            HashtagTag.TAG_NAME -> {
                HashtagTag
                    .parseLowercase(
                        tag,
                    )?.let { Nip32LabelTarget.OfTag(HashtagTag.TAG_NAME, ValueType.HASHTAG, it) }
            }

            ReferenceTag.TAG_NAME -> {
                ReferenceTag.parse(tag)?.let { Nip32LabelTarget.OfTag(ReferenceTag.TAG_NAME, ValueType.URL, it) }
            }

            else -> {
                null
            }
        }
    }
}
