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
package com.vitorpamplona.neo4j.eventstore.engine.derive

import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.quartz.nip01Core.core.Address

/** The four kinds of node an edge can point at, with the label each is stored under. */
enum class NodeKind(
    val label: String,
) {
    EVENT(Labels.EVENT),
    USER(Labels.USER),
    ADDRESS(Labels.ADDRESS),
    TAG(Labels.TAG),
}

/** A node by its unique key: an event id, a pubkey, an address id (`kind:pubkey:d`), or a tag key (`name:value`). */
data class NodeRef(
    val kind: NodeKind,
    val key: String,
)

/**
 * One relationship the event contributes. [props] values are only `String`, `Long` or
 * `List<String>` — the shapes Neo4j hands back — so the in-memory spec and the Neo4j binding
 * compare equal without conversion.
 */
data class EdgeDoc(
    val type: String,
    val target: NodeRef,
    val props: Map<String, Any> = emptyMap(),
)

/**
 * The one slot a replaceable or addressable event competes for (NIP-01). The projector keeps at
 * most one stored event per slot and applies the tiebreak itself (spec §6.2), because the source
 * may supersede atomically and never report the loser's removal.
 */
sealed interface Slot {
    /** Kinds 0, 3, 10000–19999: one per (pubkey, kind), found through its `by_<k>` edge. */
    data class Replaceable(
        val pubkey: String,
        val kind: Int,
        val authorType: String,
    ) : Slot

    /** Kinds 30000–39999: one per address, found through `VERSION_OF`. */
    data class Addressable(
        val address: String,
    ) : Slot
}

/**
 * What one event projects to (spec §4–§5): the event node's properties, its slot, every
 * outgoing edge (authorship and `VERSION_OF` included), and — for kind 0 only — the curated
 * values it sets on its author.
 */
data class GraphDoc(
    val id: String,
    val kind: Int,
    val createdAt: Long,
    val pubkey: String,
    val nodeProps: Map<String, Any>,
    val slot: Slot?,
    val edges: List<EdgeDoc>,
    val authorProps: Map<String, String>? = null,
)

/** An `:Address` node's properties, recovered from its id (`kind:pubkey:d`). */
data class AddressKey(
    val id: String,
    val kind: Int,
    val pubkey: String,
    val d: String,
) {
    companion object {
        fun parse(id: String): AddressKey? = Address.parse(id)?.let { AddressKey(it.toValue(), it.kind, it.pubKeyHex, it.dTag) }
    }
}

/** The NIP-01 winner between two versions of one slot: highest `created_at`, then the LOWEST id. */
fun wins(
    createdAt: Long,
    id: String,
    otherCreatedAt: Long,
    otherId: String,
): Boolean = createdAt > otherCreatedAt || (createdAt == otherCreatedAt && id < otherId)
