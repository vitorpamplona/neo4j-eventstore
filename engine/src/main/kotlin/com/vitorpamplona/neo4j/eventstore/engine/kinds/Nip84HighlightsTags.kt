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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-84's attribution `p`: `["p", <pubkey>, <relay>, <role>]`. The role says what the person is
 * to the highlighted content ([AUTHOR_ROLE], `editor`, …); in a quote highlight the
 * [MENTION_ROLE] marks someone the comment cites instead. Main reads a highlight's `p` only as a
 * plain `PTag`, without its role.
 */
internal data class Nip84AttributionTag(
    val pubKey: HexKey,
    val role: String? = null,
) {
    fun isMention() = role == MENTION_ROLE

    /** The person's role on the link to them, when the tag names one. */
    fun linkProps() = RoleProps(listOfNotNull(role))

    companion object {
        const val TAG_NAME = "p"

        const val AUTHOR_ROLE = "author"
        const val MENTION_ROLE = "mention"

        fun parse(tag: Array<String>): Nip84AttributionTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            return Nip84AttributionTag(tag[1], tag.getOrNull(3)?.ifBlank { null })
        }
    }
}

/**
 * NIP-84's `r` tag: `["r", <url or text>, <marker>]`. The value names the highlighted source, and
 * NIP-84 lets it be any text, not only a URL. In a quote highlight the [MENTION_MARKER] marks a
 * URL the comment cites instead, and [SOURCE_MARKER] the source itself. Main's `ReferenceTag`
 * reads no marker.
 */
internal data class Nip84MarkedReferenceTag(
    val reference: String,
    val marker: String? = null,
) {
    fun isMention() = marker == MENTION_MARKER

    companion object {
        const val TAG_NAME = "r"

        const val SOURCE_MARKER = "source"
        const val MENTION_MARKER = "mention"

        fun parse(tag: Array<String>): Nip84MarkedReferenceTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            return Nip84MarkedReferenceTag(tag[1], tag.getOrNull(2)?.ifBlank { null })
        }
    }
}
