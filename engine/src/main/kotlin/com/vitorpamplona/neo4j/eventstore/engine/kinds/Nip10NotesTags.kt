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
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip10Notes.BaseThreadedEvent
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.utils.ensure
import com.vitorpamplona.quartz.utils.lastNotNullOfOrNull

/**
 * NIP-10's marked `a` tag, `["a", <address>, <relay>, <marker>]`: the addressable counterpart of
 * [MarkedETag]. Main has no parser that reads an `a` tag's marker.
 */
internal data class Nip10MarkedATag(
    val address: Address,
    val relay: NormalizedRelayUrl? = null,
    val marker: MarkedETag.MARKER? = null,
) {
    companion object {
        const val TAG_NAME = "a"

        const val ORDER_ADDRESS = 1
        const val ORDER_RELAY = 2
        const val ORDER_MARKER = 3

        fun parse(tag: Array<String>): Nip10MarkedATag? {
            ensure(tag.has(ORDER_ADDRESS)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[ORDER_ADDRESS].isNotEmpty()) { return null }

            val address = Address.parse(tag[ORDER_ADDRESS]) ?: return null
            val relay = tag.getOrNull(ORDER_RELAY)?.let { RelayUrlNormalizer.normalizeOrNull(it) }
            val marker = tag.getOrNull(ORDER_MARKER)?.let { MarkedETag.MARKER.parse(it) }

            return Nip10MarkedATag(address, relay, marker)
        }

        fun parseRoot(tag: Array<String>): Nip10MarkedATag? {
            ensure(tag.has(ORDER_MARKER)) { return null }
            ensure(tag[ORDER_MARKER] == MarkedETag.MARKER.ROOT.code) { return null }
            return parse(tag)
        }

        fun parseReply(tag: Array<String>): Nip10MarkedATag? {
            ensure(tag.has(ORDER_MARKER)) { return null }
            ensure(tag[ORDER_MARKER] == MarkedETag.MARKER.REPLY.code) { return null }
            return parse(tag)
        }
    }
}

/**
 * The first `root`-marked `a` ([Nip10MarkedATag]): the thread's root when it is an addressable
 * event. A NIP-72 community's `a` never counts, even marked: a note is posted in a community,
 * it does not reply to it. Main's [BaseThreadedEvent] reads only `e` markers.
 */
internal fun BaseThreadedEvent.nip10MarkedRootAddress() =
    tags.firstNotNullOfOrNull { tag -> Nip10MarkedATag.parseRoot(tag)?.takeUnless { it.address.kind == CommunityDefinitionEvent.KIND } }

/** The last `reply`-marked `a` ([Nip10MarkedATag]), skipping a community's as [nip10MarkedRootAddress] does. */
internal fun BaseThreadedEvent.nip10MarkedReplyAddress() =
    tags.lastNotNullOfOrNull { tag -> Nip10MarkedATag.parseReply(tag)?.takeUnless { it.address.kind == CommunityDefinitionEvent.KIND } }
