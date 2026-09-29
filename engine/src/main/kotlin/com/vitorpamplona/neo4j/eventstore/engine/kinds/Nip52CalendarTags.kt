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
import com.vitorpamplona.quartz.utils.ensure

/**
 * A NIP-52 date or time slot's participant: a `p` tag whose fourth slot is the role the person
 * has in the meeting, free text: `["p", "<pubkey>", "<optional relay>", "<role>"]`. Main's `PTag`
 * does not read the role.
 */
internal data class Nip52SlotParticipant(
    val pubKey: HexKey,
    val role: String? = null,
) {
    /** The role as the link's props: one list whether or not the tag names a role. */
    fun linkProps() = ParticipantProps(listOfNotNull(role))
}

/** Main has no parser for a slot participant's role; see [Nip52SlotParticipant]. */
internal object Nip52SlotParticipantTag {
    const val TAG_NAME = "p"

    fun parse(tag: Array<String>): Nip52SlotParticipant? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].length == 64) { return null }
        return Nip52SlotParticipant(tag[1], tag.getOrNull(3)?.ifBlank { null })
    }
}
