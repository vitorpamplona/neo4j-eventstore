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

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.utils.ensure

// The NIP-10 readers here skip the relay hint. Main's `MarkedETag` parsers (and every Tag class
// that keeps a hint) normalize it through Quartz's global, synchronized relay-url cache on each
// tag, and no link reads a hint: measured on the mappers, the hints were most of a note's cost,
// and under several threads the cache's lock made it worse.

/**
 * NIP-10's `e` tag, `["e", <id>, <relay>, <marker>, <pubkey>]`, without its relay hint: the id,
 * the marker and the pubkey. Both are found where main's [MarkedETag] finds them (the marker in
 * slot 3, else 4, else 2; the pubkey as the first 64-char value of slots 3, 4 and 2), so the
 * variants it documents — a pubkey in the marker slot, a marker after the pubkey — read alike.
 */
internal class Nip10ETag(
    val eventId: HexKey,
    val marker: MarkedETag.MARKER? = null,
    val author: HexKey? = null,
) {
    companion object {
        const val TAG_NAME = MarkedETag.TAG_NAME

        const val ORDER_EVT_ID = 1
        const val ORDER_RELAY = 2
        const val ORDER_MARKER = 3
        const val ORDER_PUBKEY = 4

        fun parse(tag: Array<String>): Nip10ETag? {
            ensure(tag.has(ORDER_EVT_ID)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[ORDER_EVT_ID].length == 64) { return null }
            return Nip10ETag(tag[ORDER_EVT_ID], pickMarker(tag), pickAuthor(tag))
        }

        private fun pickMarker(tag: Array<String>): MarkedETag.MARKER? {
            if (tag.has(ORDER_MARKER)) MarkedETag.MARKER.parse(tag[ORDER_MARKER])?.let { return it }
            if (tag.has(ORDER_PUBKEY)) MarkedETag.MARKER.parse(tag[ORDER_PUBKEY])?.let { return it }
            if (tag.has(ORDER_RELAY)) MarkedETag.MARKER.parse(tag[ORDER_RELAY])?.let { return it }
            return null
        }

        private fun pickAuthor(tag: Array<String>): HexKey? {
            if (tag.has(ORDER_MARKER) && tag[ORDER_MARKER].length == 64) return tag[ORDER_MARKER]
            if (tag.has(ORDER_PUBKEY) && tag[ORDER_PUBKEY].length == 64) return tag[ORDER_PUBKEY]
            if (tag.has(ORDER_RELAY) && tag[ORDER_RELAY].length == 64) return tag[ORDER_RELAY]
            return null
        }
    }
}

/**
 * NIP-10's marked `a` tag, `["a", <address>, <relay>, <marker>]`: the addressable counterpart of
 * [Nip10ETag], without its relay hint either. Main has no parser that reads an `a` tag's marker.
 */
internal data class Nip10MarkedATag(
    val address: Address,
    val marker: MarkedETag.MARKER? = null,
) {
    /** A NIP-72 community's `a` is where a note is posted, never a thread root or parent, even marked. */
    val isCommunity get() = address.kind == CommunityDefinitionEvent.KIND

    companion object {
        const val TAG_NAME = "a"

        const val ORDER_ADDRESS = 1
        const val ORDER_MARKER = 3

        fun parse(tag: Array<String>): Nip10MarkedATag? {
            ensure(tag.has(ORDER_ADDRESS)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[ORDER_ADDRESS].isNotEmpty()) { return null }

            val address = Address.parse(tag[ORDER_ADDRESS]) ?: return null
            val marker = tag.getOrNull(ORDER_MARKER)?.let { MarkedETag.MARKER.parse(it) }

            return Nip10MarkedATag(address, marker)
        }
    }
}

/**
 * A note's NIP-10 thread: its `e` ([Nip10ETag]) and `a` ([Nip10MarkedATag]) tags, parsed in one
 * pass, and which of them are the thread's root and the note's parent.
 *
 * - **Marked** (any `root` or `reply` marker on an `e`, or on an `a` that is not a community's):
 *   the root is the first `root`-marked `e` / `a`, the parent the last `reply`-marked one. A lone
 *   `reply` marker is a direct reply, so its target is the root too; a lone `root` marker is a
 *   reply to the root, so it is the parent too. The unmarked tags beside markers are NOT read by
 *   position: NIP-10 deprecates positional tags, and a client that marks the thread does not
 *   leave its structure to order, so they are mentions.
 * - **Positional** (no marker at all): the first unmarked `e` is the root, the last the parent,
 *   and any in between are mentions. "Unmarked" is by the marker, not by the tag's length: an
 *   empty marker slot (`["e", <id>, "", ""]`) or a pubkey in it (`["e", <id>, <relay>,
 *   <pubkey>]`) marks nothing.
 *
 * Main's `BaseThreadedEvent.root()` / `unmarkedReply()` read positional tags only when they have
 * two or three slots, and fall back to them beside a lone `reply` marker.
 */
internal class Nip10Thread(
    val eTags: List<Nip10ETag>,
    val aTags: List<Nip10MarkedATag>,
) {
    val root: Nip10ETag?
    val parent: Nip10ETag?
    val rootAddress: Address?
    val parentAddress: Address?

    init {
        val rootE = eTags.firstOrNull { it.marker == MarkedETag.MARKER.ROOT }
        val replyE = eTags.lastOrNull { it.marker == MarkedETag.MARKER.REPLY }
        val rootA = aTags.firstOrNull { it.marker == MarkedETag.MARKER.ROOT && !it.isCommunity }?.address
        val replyA = aTags.lastOrNull { it.marker == MarkedETag.MARKER.REPLY && !it.isCommunity }?.address

        if (rootE != null || replyE != null || rootA != null || replyA != null) {
            val hasRoot = rootE != null || rootA != null
            val hasReply = replyE != null || replyA != null
            root = if (hasRoot) rootE else replyE
            rootAddress = if (hasRoot) rootA else replyA
            parent = if (hasReply) replyE else rootE
            parentAddress = if (hasReply) replyA else rootA
        } else {
            root = eTags.firstOrNull { it.marker == null }
            parent = eTags.lastOrNull { it.marker == null }
            rootAddress = null
            parentAddress = null
        }
    }

    /** The parent's author as the parent tag itself names it: the `e`'s pubkey slot, else the `a`'s coordinate. */
    val parentAuthor: HexKey? get() = parent?.author ?: parentAddress?.pubKeyHex
}

/** [Nip10Thread] of these tags: one walk, both tag names. */
internal fun TagArray.nip10Thread(): Nip10Thread {
    val eTags = ArrayList<Nip10ETag>()
    val aTags = ArrayList<Nip10MarkedATag>()
    for (tag in this) {
        Nip10ETag.parse(tag)?.let { eTags.add(it) } ?: Nip10MarkedATag.parse(tag)?.let { aTags.add(it) }
    }
    return Nip10Thread(eTags, aTags)
}
