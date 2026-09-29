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
import com.vitorpamplona.quartz.experimental.audio.track.tags.ParticipantTag
import com.vitorpamplona.quartz.experimental.publications.PublicationContentEvent
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag

/** Birdstar's NIP-73 `["i", "<wikidata-entity-url>"]`: the species an event is about. Main's Quartz has no parser for it. */
internal object ExperimentalSpeciesIdTag {
    const val TAG_NAME = "i"

    fun parse(tag: Array<String>): String? {
        if (!tag.has(1) || tag[0] != TAG_NAME || tag[1].isBlank()) return null
        return tag[1]
    }
}

/**
 * A publication section's back reference to its index, `["T", "<d>"]` (the spelling most
 * publishers use; a few write `c`). Main's Quartz reads it only inside
 * `PublicationContentEvent.publicationIdentifier()`, which does not say which spelling it read.
 */
internal object ExperimentalPublicationIdTag {
    const val TAG_NAME = PublicationContentEvent.PUBLICATION_TAG
    const val ALT_TAG_NAME = PublicationContentEvent.PUBLICATION_TAG_ALT

    fun parse(tag: Array<String>): String? {
        if (!tag.has(1) || tag[0] != TAG_NAME || tag[1].isEmpty()) return null
        return tag[1]
    }
}

/**
 * An `a` tag split by its NIP-54 `fork` marker (`["a", <address>, <relay>, "fork"]`). Main's
 * Quartz has only `parseForkedAddress`, which drops the relay hint and has no unmarked twin.
 */
internal object ExperimentalForkATag {
    /** The `a` marked `fork`: the version this event was forked from. */
    fun parseForked(tag: Array<String>): ATag? {
        if (!isForkMarked(tag)) return null
        return ATag.parse(tag)
    }

    /** An `a` that is not marked `fork`: a plain reference (a mention, a community). */
    fun parseUnforked(tag: Array<String>): ATag? {
        if (isForkMarked(tag)) return null
        return ATag.parse(tag)
    }

    private fun isForkMarked(tag: Array<String>) =
        tag.has(MarkedETag.ORDER_MARKER) && tag[MarkedETag.ORDER_MARKER] == MarkedETag.MARKER.FORK.code
}

/** A Zapstr track participant with the role written in the 4th slot of its `p` (Host, Artist…). */
internal class ExperimentalAudioParticipant(
    val tag: ParticipantTag,
    val role: String?,
) {
    fun linkProps() = ParticipantProps(roles = listOfNotNull(role))
}

/** Main's `ParticipantTag.parse` drops the 4th slot, Zapstr's role; this keeps it (blank is none). */
internal object ExperimentalAudioParticipantTag {
    const val TAG_NAME = ParticipantTag.TAG_NAME

    fun parse(tag: Array<String>): ExperimentalAudioParticipant? {
        val participant = ParticipantTag.parse(tag) ?: return null
        return ExperimentalAudioParticipant(participant, tag.getOrNull(3)?.ifBlank { null })
    }
}
