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
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyEventTag
import com.vitorpamplona.quartz.utils.ensure

/** A NIP-22 parent item that is an event: its id, and its author when the tag names one. */
internal data class Nip22ParentEvent(
    val eventId: HexKey,
    val author: HexKey?,
)

/**
 * A NIP-22 parent-item `e`, `["e", <id>, <relay>, <pubkey>]`, as a [Nip22ParentEvent], without
 * the relay hint (main's [ReplyEventTag.parse] normalizes it through Quartz's global,
 * synchronized relay-url cache, and no link uses it).
 *
 * The author is whichever of slots 3 and 4 holds a 64-char value: NIP-22 puts it in slot 3, but
 * comments written by NIP-10 habit put a marker there and the pubkey after it
 * (`["e", <id>, "", "reply", <pubkey>]`), and main's [ReplyEventTag] reads only slot 3.
 */
internal object Nip22ParentETag {
    const val TAG_NAME = ReplyEventTag.TAG_NAME

    const val ORDER_EVT_ID = 1
    const val ORDER_PUBKEY = 3
    const val ORDER_NIP10_PUBKEY = 4

    fun parse(tag: Array<String>): Nip22ParentEvent? {
        ensure(tag.has(ORDER_EVT_ID)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[ORDER_EVT_ID].length == 64) { return null }
        return Nip22ParentEvent(tag[ORDER_EVT_ID], pickAuthor(tag))
    }

    private fun pickAuthor(tag: Array<String>): HexKey? {
        if (tag.has(ORDER_PUBKEY) && tag[ORDER_PUBKEY].length == 64) return tag[ORDER_PUBKEY]
        if (tag.has(ORDER_NIP10_PUBKEY) && tag[ORDER_NIP10_PUBKEY].length == 64) return tag[ORDER_NIP10_PUBKEY]
        return null
    }
}
