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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ParticipantProps
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.utils.ensure

// Parsers the NIP-53 mappers need that the pinned Quartz lacks.

/**
 * The `a` tag a NIP-53 chat message names its activity (a 30311 stream, a 30312 space) with.
 * The spec's example marks it `root`, which tells it apart from an activity the message only
 * mentions: `["a", "<kind>:<pubkey>:<d>", "<optional relay>", "root"]`. Main's `ATag` drops the marker.
 */
internal data class Nip53Activity(
    val activity: ATag,
    val marker: String? = null,
) {
    fun isRoot() = marker == Nip53ActivityTag.ROOT_MARKER
}

/** Main has no parser for a chat message's marked activity `a`; see [Nip53Activity]. */
internal object Nip53ActivityTag {
    const val TAG_NAME = ATag.TAG_NAME
    const val ROOT_MARKER = "root"

    fun parse(tag: Array<String>): Nip53Activity? {
        val activity = ATag.parse(tag) ?: return null
        return Nip53Activity(activity, tag.getOrNull(3)?.ifEmpty { null })
    }
}

/**
 * Main has no parser for zap.stream's `goal` tag: the NIP-75 zap goal (kind 9041) a live stream
 * raises toward, by event id: `["goal", "<9041 event id>"]`.
 */
internal object Nip53GoalTag {
    const val TAG_NAME = "goal"

    fun parse(tag: Array<String>): HexKey? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return tag[1]
    }
}

/** The role and proof as written (blank ones are absent), as the link's props. Main's [ParticipantTag] has no `linkProps()`. */
internal fun ParticipantTag.nip53LinkProps() = ParticipantProps(listOfNotNull(role?.ifBlank { null }), proof?.ifBlank { null })
