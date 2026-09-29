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

import com.vitorpamplona.neo4j.eventstore.engine.kinds.KindLinks
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Link
import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkTarget
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.utils.EventFactory

/**
 * Event → [GraphDoc]: the whole graph schema as ONE pure function (spec §5).
 *
 * The live projector, the in-memory executable spec and the bulk CSV writer all call this, so
 * they can disagree about how a graph is STORED but never about what it IS.
 *
 * What an event's references MEAN is the vocabulary's job ([KindLinks]: every link the event
 * states, typed by relation, `docs/vocabulary.md`). This turns links into edges — the relation's
 * name is the relationship type, its props and `via` are the edge's properties — and applies the
 * rules that are about STORAGE, not meaning: the nsec rule ([Secrets]), the `:Tag` value bound
 * ([GraphPolicy.maxTagValueBytes]), no edge from an event to itself, the NIP-01 slot, and the
 * curated node values ([Extractors]).
 */
class EdgeDeriver(
    val policy: GraphPolicy = GraphPolicy.Default,
) {
    fun derive(input: Event): GraphDoc {
        val event = typed(input)
        val secrets = Secrets.inContent(event.content)

        val edges = ArrayList<EdgeDoc>()
        for (link in KindLinks.of(event)) {
            val target = nodeRef(link.target) ?: continue
            if (target.kind == NodeKind.EVENT && target.key == event.id) continue
            // The ADDRESS edge is the event's own slot: its key is the event's pubkey and `d`,
            // never a pasted secret, and the slot must exist for supersession to work.
            if (link.relation != Relation.ADDRESS && Secrets.leaks(target.key, secrets)) continue
            val props = storeProps(link, secrets) ?: continue
            edges.add(EdgeDoc(link.relation.name, target, props))
        }

        val nodeProps = HashMap<String, Any>()
        // The slot is the ADDRESS link the vocabulary emitted, so it is validated exactly as the
        // edge is: an event whose own address is malformed (a non-hex pubkey) competes for none.
        val slot = edges.firstOrNull { it.type == Relation.ADDRESS.name }?.let { Slot(it.target.key) }
        if (slot != null && event.kind.isAddressable()) {
            AddressKey.parse(slot.address)?.let { nodeProps[D] = it.d }
        }
        expiration(event.tags)?.let { nodeProps[EXPIRES_AT] = it }
        nodeProps.putAll(Extractors.nodeValues(event, policy))

        return GraphDoc(
            id = event.id,
            kind = event.kind,
            createdAt = event.createdAt,
            pubkey = event.pubKey,
            nodeProps = nodeProps,
            slot = slot,
            edges = edges,
            authorProps = Extractors.authorValues(event, policy),
        )
    }

    // Targets arrive in key form: LinkBuilder lowercased and validated every id, key and address.
    private fun nodeRef(target: LinkTarget): NodeRef? =
        when (target) {
            is LinkTarget.Event -> NodeRef(NodeKind.EVENT, target.id)

            is LinkTarget.User -> NodeRef(NodeKind.USER, target.pubkey)

            is LinkTarget.Address -> NodeRef(NodeKind.ADDRESS, target.value)

            // A `:Tag` key sits behind a uniqueness constraint: an unbounded value would fail the
            // event's transaction on every retry, and a long one is no value to join on anyway.
            is LinkTarget.Tag -> if (policy.fitsTagNode(target.value)) NodeRef(NodeKind.TAG, target.name + ":" + target.value) else null
        }

    /**
     * [link]'s edge properties: its props in store form plus its `via`, with numbers widened to
     * the `Long` / `Double` Neo4j returns. Null when a value carries a private key: the link is
     * then dropped whole rather than stored without the qualifier it was made with.
     */
    private fun storeProps(
        link: Link<*>,
        secrets: Set<String>,
    ): Map<String, Any>? {
        val source = link.props?.toMap()
        if (source.isNullOrEmpty() && link.via == null) return emptyMap()
        val out = HashMap<String, Any>()
        source?.forEach { (key, value) ->
            val stored: Any =
                when (value) {
                    is Int -> value.toLong()
                    is Short -> value.toLong()
                    is Byte -> value.toLong()
                    is Float -> value.toDouble()
                    is String -> if (Secrets.leaks(value, secrets)) return null else value
                    is List<*> -> value.map { it.toString() }.also { list -> if (list.any { Secrets.leaks(it, secrets) }) return null }
                    else -> value
                }
            out[key] = stored
        }
        link.via?.let { out[VIA] = it }
        return out
    }

    companion object {
        const val VIA = "via"
        const val D = "d"
        const val EXPIRES_AT = "expires_at"

        /**
         * The typed Quartz class for [event] — the kind mappers are registered by class. A plain
         * [Event] of a known kind (as a store hands back) is re-created through [EventFactory].
         */
        fun typed(event: Event): Event {
            if (event::class != Event::class) return event
            return runCatching {
                EventFactory.create<Event>(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig)
            }.getOrDefault(event)
        }

        private fun expiration(tags: Array<Array<String>>): Long? =
            tags
                .firstOrNull {
                    it.size >= 2 && it[0] == "expiration"
                }?.get(1)
                ?.toLongOrNull()
    }
}
