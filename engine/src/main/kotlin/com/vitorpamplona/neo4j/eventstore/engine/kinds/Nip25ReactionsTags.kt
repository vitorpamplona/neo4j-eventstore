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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.utils.ensure

/** What a NIP-25 reaction reacted to: the event's id, and its author when the tag names one. */
internal data class Nip25ReactedEvent(
    val eventId: HexKey,
    val author: HexKey?,
)

/**
 * A NIP-25 reaction's `e`, `["e", <event-id>, <relay-hint>, <pubkey>]`, as a [Nip25ReactedEvent],
 * without the relay hint. Main's [ETag.parse] reads both, but normalizes the hint through Quartz's
 * global, synchronized relay-url cache, which no link uses; its `parseId` drops the pubkey. The
 * pubkey is found where [ETag] finds it (the first 64-char value of slots 2-4).
 */
internal object Nip25ReactedETag {
    const val TAG_NAME = ETag.TAG_NAME

    fun parse(tag: Array<String>): Nip25ReactedEvent? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].length == 64) { return null }
        return Nip25ReactedEvent(tag[1], pickAuthor(tag))
    }

    private fun pickAuthor(tag: Array<String>): HexKey? {
        if (tag.has(2) && tag[2].length == 64) return tag[2]
        if (tag.has(3) && tag[3].length == 64) return tag[3]
        if (tag.has(4) && tag[4].length == 64) return tag[4]
        return null
    }
}
