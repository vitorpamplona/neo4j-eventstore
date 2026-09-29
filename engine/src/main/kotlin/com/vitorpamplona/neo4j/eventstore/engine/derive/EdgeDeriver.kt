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

import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.KindRegistry
import com.vitorpamplona.neo4j.eventstore.engine.schema.RelTypes
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.tags.isIndexableTagName
import com.vitorpamplona.quartz.utils.EventFactory

/**
 * Event → [GraphDoc]: the whole graph schema as ONE pure function (spec §5).
 *
 * The live projector, the in-memory executable spec and the bulk CSV writer all call this, so
 * they can disagree about how a graph is STORED but never about what it IS.
 *
 * The three steps:
 * 1. Ask Quartz's hint providers which ids the event links ([EventHintProvider],
 *    [PubKeyHintProvider], [AddressHintProvider]) — they know kind semantics a value's shape
 *    cannot reveal (a `z` parent list, NIP-22 `E`/`A`/`P`, NIP-58 badge tags).
 * 2. Walk every single-letter tag and give it ONE home: a provider-named target, a NIP-85
 *    subject, a shape-based fallback for kinds Quartz does not type, a `:Tag` node if the policy
 *    allowlists the name, or nothing.
 * 3. Every provider-named id no literal tag produced becomes a DERIVED edge (`ref_…`, with
 *    `via`), plus the [LinkRules] that fill gaps Quartz leaves open.
 */
class EdgeDeriver(
    val registry: KindRegistry = KindRegistry.quartzKnownKinds(),
    val policy: GraphPolicy = GraphPolicy.Default,
) {
    fun derive(input: Event): GraphDoc {
        val event = typed(input)
        val kind = event.kind
        val segment = registry.segment(kind)
        val bucketed = segment == RelTypes.OTHER
        val tags = event.tags
        val out = EdgeCollector()

        // Step 1 — what Quartz says this kind links, validated to canonical keys.
        val secrets = LinkRules.contentSecrets(event.content)
        val linkedEvents = providerSet { (event as? EventHintProvider)?.linkedEventIds() }.filterCanonicalHex() - event.id
        val linkedUsers = providerSet { (event as? PubKeyHintProvider)?.linkedPubKeys() }.filterCanonicalHex() - secrets
        val linkedAddresses =
            providerSet { (event as? AddressHintProvider)?.linkedAddressIds() }.mapNotNullTo(
                HashSet(),
            ) { canonicalAddress(it) }

        // Step 2 — every single-letter tag gets exactly one home (or is dropped).
        val literalEvents = HashSet<String>()
        val literalUsers = HashSet<String>()
        val literalAddresses = HashSet<String>()
        val literalUppercaseP = HashSet<String>()
        val roles = RoleTable.Context(kind, tags)

        tags.forEachIndexed { index, tag ->
            if (tag.size < 2) return@forEachIndexed
            val name = tag[0]
            if (!isIndexableTagName(name)) return@forEachIndexed
            val value = tag[1]
            // A key the content reveals as a PRIVATE key never becomes a node, from any tag.
            if (value in secrets) return@forEachIndexed
            val target = classify(kind, name, value, linkedEvents, linkedUsers, linkedAddresses)
            val type = RelTypes.literal(name, segment)
            if (target != null) {
                if (target.kind == NodeKind.EVENT && target.key == event.id) return@forEachIndexed
                when (target.kind) {
                    NodeKind.EVENT -> literalEvents += target.key
                    NodeKind.USER -> literalUsers += target.key
                    NodeKind.ADDRESS -> literalAddresses += target.key
                    NodeKind.TAG -> Unit
                }
                if (name == "P") literalUppercaseP += target.key
                val props = HashMap<String, Any>()
                RoleTable.storedRoles(roles, index, tag)?.let { props[ROLES] = it }
                if (bucketed) props[KIND] = kind.toLong()
                Extractors.edgeValues(event, name, tag, target, policy)?.let { props.putAll(it) }
                out.add(EdgeDoc(type, target, props))
            } else if (policy.isTagNode(name, value)) {
                out.add(EdgeDoc(type, NodeRef(NodeKind.TAG, "$name:$value"), if (bucketed) mapOf(KIND to kind.toLong()) else emptyMap()))
            }
        }

        // Step 3 — derived references: named by a provider, produced by no literal tag.
        val vias = LinkRules.multiLetterVias(tags)
        for (id in linkedEvents) {
            if (id !in
                literalEvents
            ) {
                out.add(derived('e', segment, bucketed, kind, NodeRef(NodeKind.EVENT, id), vias[id]))
            }
        }
        for (pk in linkedUsers) {
            if (pk !in
                literalUsers
            ) {
                out.add(derived('p', segment, bucketed, kind, NodeRef(NodeKind.USER, pk), vias[pk]))
            }
        }
        for (a in linkedAddresses) {
            if (a !in
                literalAddresses
            ) {
                out.add(derived('a', segment, bucketed, kind, NodeRef(NodeKind.ADDRESS, a), vias[a]))
            }
        }

        LinkRules.extraLinks(event, literalEvents, literalUsers, literalUppercaseP, secrets).forEach { link ->
            val props = HashMap<String, Any>()
            props[VIA] = link.via
            link.roles?.let { props[ROLES] = it }
            if (bucketed) props[KIND] = kind.toLong()
            out.add(EdgeDoc(RelTypes.derived(link.family, segment), link.target, props))
        }

        // Authorship, and the event's own slot.
        val authorType = RelTypes.authored(segment)
        out.add(EdgeDoc(authorType, NodeRef(NodeKind.USER, event.pubKey), if (bucketed) mapOf(KIND to kind.toLong()) else emptyMap()))

        val nodeProps = HashMap<String, Any>()
        val slot: Slot? =
            when {
                kind.isAddressable() -> {
                    val d = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
                    nodeProps[D] = d
                    val address = Address.assemble(kind, event.pubKey, d)
                    out.add(EdgeDoc(RelTypes.VERSION_OF, NodeRef(NodeKind.ADDRESS, address)))
                    Slot.Addressable(address)
                }

                kind.isReplaceable() -> {
                    Slot.Replaceable(event.pubKey, kind, authorType)
                }

                else -> {
                    null
                }
            }
        expiration(tags)?.let { nodeProps[EXPIRES_AT] = it }
        nodeProps.putAll(Extractors.nodeValues(event, policy))

        return GraphDoc(
            id = event.id,
            kind = kind,
            createdAt = event.createdAt,
            pubkey = event.pubKey,
            nodeProps = nodeProps,
            slot = slot,
            // Last line of the nsec rule: whatever path a revealed private key took (a provider
            // set, a derived link, an address's pubkey part), no edge may point at it.
            edges =
                if (secrets.isEmpty()) {
                    out.edges()
                } else {
                    out.edges().filter { e ->
                        e.type == RelTypes.VERSION_OF ||
                            secrets.none { e.target.key.contains(it) }
                    }
                },
            authorProps = Extractors.authorValues(event, policy),
        )
    }

    private fun classify(
        kind: Int,
        name: String,
        value: String,
        linkedEvents: Set<String>,
        linkedUsers: Set<String>,
        linkedAddresses: Set<String>,
    ): NodeRef? {
        // Rules 1–3: the providers name it.
        if (value in linkedEvents) return NodeRef(NodeKind.EVENT, value)
        if (value in linkedUsers) return NodeRef(NodeKind.USER, value)
        val address = canonicalAddress(value)
        if (address != null && address in linkedAddresses) return NodeRef(NodeKind.ADDRESS, address)
        // Rule 4: NIP-85 assertion subjects live in `d`, which no provider reports.
        if (name == "d") {
            return when (kind) {
                30382 -> if (isCanonicalHex64(value)) NodeRef(NodeKind.USER, value) else null
                30383 -> if (isCanonicalHex64(value)) NodeRef(NodeKind.EVENT, value) else null
                30384 -> address?.let { NodeRef(NodeKind.ADDRESS, it) }
                else -> null
            }
        }
        // Rule 5: kinds Quartz does not type (or types without a provider) — by name and shape.
        // `q` uses the shape too: QTag.parseAddressId rejects every address (it refuses a ':').
        return when (name) {
            "e", "E" -> {
                if (isCanonicalHex64(value)) NodeRef(NodeKind.EVENT, value) else null
            }

            "p", "P" -> {
                if (isCanonicalHex64(value)) NodeRef(NodeKind.USER, value) else null
            }

            "a", "A" -> {
                address?.let { NodeRef(NodeKind.ADDRESS, it) }
            }

            "q" -> {
                when {
                    isCanonicalHex64(value) -> NodeRef(NodeKind.EVENT, value)
                    address != null -> NodeRef(NodeKind.ADDRESS, address)
                    else -> null
                }
            }

            else -> {
                null
            }
        }
    }

    private fun derived(
        family: Char,
        segment: String,
        bucketed: Boolean,
        kind: Int,
        target: NodeRef,
        via: String?,
    ): EdgeDoc {
        val props = HashMap<String, Any>()
        props[VIA] = via ?: LinkRules.VIA_CONTENT
        if (bucketed) props[KIND] = kind.toLong()
        return EdgeDoc(RelTypes.derived(family, segment), target, props)
    }

    companion object {
        const val ROLES = "roles"
        const val VIA = "via"
        const val KIND = "kind"
        const val D = "d"
        const val EXPIRES_AT = "expires_at"

        /**
         * The typed Quartz class for [event] — hint providers live on the subclasses. A plain
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

        // A provider parses the event's tags lazily; a malformed tag must not lose the rest of the
        // event's graph, so a throwing provider contributes nothing.
        private inline fun providerSet(block: () -> List<String>?): List<String> = runCatching(block).getOrNull() ?: emptyList()

        private fun List<String>.filterCanonicalHex(): Set<String> = filterTo(HashSet()) { isCanonicalHex64(it) }
    }
}

/** Collects edges, collapsing duplicates to one per (type, target) with their roles unioned. */
private class EdgeCollector {
    private val byKey = LinkedHashMap<Pair<String, NodeRef>, EdgeDoc>()

    fun add(edge: EdgeDoc) {
        val key = edge.type to edge.target
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = edge
            return
        }
        @Suppress("UNCHECKED_CAST")
        val a = existing.props[EdgeDeriver.ROLES] as List<String>?

        @Suppress("UNCHECKED_CAST")
        val b = edge.props[EdgeDeriver.ROLES] as List<String>?
        if (b != null && a != b) {
            val merged = ((a ?: emptyList()) + b).distinct()
            byKey[key] = existing.copy(props = existing.props + (EdgeDeriver.ROLES to merged))
        }
    }

    fun edges(): List<EdgeDoc> = byKey.values.toList()
}
