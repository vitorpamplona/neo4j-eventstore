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
package com.vitorpamplona.neo4j.eventstore.engine.vocab

import com.vitorpamplona.neo4j.eventstore.engine.schema.canonicalAddress
import com.vitorpamplona.neo4j.eventstore.engine.schema.isCanonicalHex64
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.LinkProps
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.GenericETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PubKeyReferenceTag

/**
 * Collects an event's links. A kind's mapper hands it values Quartz's Tag classes parsed (a
 * [GenericETag], a [PubKeyReferenceTag], an [ATag], or an id / address / value an accessor
 * returned): reading tag slots is the parsers' job, not the builder's or the mapper's.
 *
 * Every relation takes only its declared props type ([Relation]`<P>`), so a mismatched pairing
 * does not compile. Targets are checked here as the last guard before a value becomes a node:
 * ids and keys are 64-hex, lowercased so one key is one node; addresses are `kind:<64-hex>:d`
 * in their canonical key form ([canonicalAddress]); values are non-blank. Props with no
 * value present are dropped, and exact duplicates collapse, keeping the first occurrence's order.
 * A malformed value is dropped, never linked.
 *
 * Two rules hold for every link a mapper states, so no mapper can break them:
 * - `via` is required: where in the event the reference was written (a tag name, or
 *   [Link.VIA_CONTENT]). Only the envelope's own links (`AUTHOR`, `ADDRESS`) have none.
 * - the target must be one its relation declares ([Relation.targets]); anything else throws: a
 *   mapper bug, which the golden tests catch and `KindLinks.of` counts per class in production.
 */
class LinkBuilder {
    private val links = LinkedHashSet<Link<*>>()

    fun <P : LinkProps> add(link: Link<P>) {
        require(link.target.kind in link.relation.targets) {
            "${link.relation} does not take a ${link.target.kind.code} target (${link.target})"
        }
        links.add(link)
    }

    /** The event's author, from its envelope: the one user link with no `via`. */
    internal fun envelopeAuthor(pubkey: String?) {
        val hex = normalizedHex(pubkey) ?: return
        add(Link(Relation.AUTHOR, LinkTarget.User(hex)))
    }

    /** The event's own address, from its envelope: the one address link with no `via`. */
    internal fun envelopeAddress(address: String?) {
        val value = normalizedAddress(address) ?: return
        add(Link(Relation.ADDRESS, LinkTarget.Address(value)))
    }

    fun <P : LinkProps> event(
        relation: Relation<P>,
        id: String?,
        via: String,
        props: P? = null,
    ) {
        val hex = normalizedHex(id) ?: return
        add(Link(relation, LinkTarget.Event(hex), via, props.orNull()))
    }

    fun <P : LinkProps> event(
        relation: Relation<P>,
        tag: GenericETag?,
        via: String,
        props: P? = null,
    ) {
        if (tag != null) event(relation, tag.eventId, via, props)
    }

    fun <P : LinkProps> user(
        relation: Relation<P>,
        pubkey: String?,
        via: String,
        props: P? = null,
    ) {
        val hex = normalizedHex(pubkey) ?: return
        add(Link(relation, LinkTarget.User(hex), via, props.orNull()))
    }

    fun <P : LinkProps> user(
        relation: Relation<P>,
        tag: PubKeyReferenceTag?,
        via: String,
        props: P? = null,
    ) {
        if (tag != null) user(relation, tag.pubKey, via, props)
    }

    fun <P : LinkProps> address(
        relation: Relation<P>,
        address: String?,
        via: String,
        props: P? = null,
    ) {
        val value = normalizedAddress(address) ?: return
        add(Link(relation, LinkTarget.Address(value), via, props.orNull()))
    }

    fun <P : LinkProps> address(
        relation: Relation<P>,
        address: Address?,
        via: String,
        props: P? = null,
    ) {
        if (address != null) address(relation, address.toValue(), via, props)
    }

    fun <P : LinkProps> address(
        relation: Relation<P>,
        tag: ATag?,
        via: String,
        props: P? = null,
    ) {
        if (tag != null) address(relation, tag.toTag(), via, props)
    }

    /** An event id or an address, told apart by shape: for slots that hold either (`q`, a NIP-22 scope). */
    fun <P : LinkProps> eventOrAddress(
        relation: Relation<P>,
        value: String?,
        via: String,
        props: P? = null,
    ) {
        if (value == null) return
        if (value.length == 64) event(relation, value, via, props) else address(relation, value, via, props)
    }

    /**
     * A value that is not an event, a user or an address: a hashtag, a URL, a kind, a group id.
     * [type] is what the value IS, and keys the node with [value] in its normal form
     * ([ValueType.normalize]); [via] is the tag it was written in.
     */
    fun <P : LinkProps> value(
        relation: Relation<P>,
        type: ValueType,
        value: String?,
        via: String,
        props: P? = null,
    ) {
        if (value.isNullOrBlank()) return
        add(Link(relation, LinkTarget.Tag(type, type.normalize(value)), via, props.orNull()))
    }

    /**
     * A NIP-73 external content id (an `i` / `I`), resolved to the value it names
     * ([ValueType.ofExternal]): a URL, a hashtag, a geohash, or an [ValueType.EXTERNAL] id.
     */
    fun <P : LinkProps> external(
        relation: Relation<P>,
        id: String?,
        via: String,
        props: P? = null,
    ) {
        val (type, value) = ValueType.ofExternal(id) ?: return
        value(relation, type, value, via, props)
    }

    fun build(): List<Link<*>> = if (links.isEmpty()) emptyList() else links.toList()

    /** Props whose every value is absent are no props: `MemberProps()` qualifies nothing. */
    private fun <P : LinkProps> P?.orNull(): P? = this?.takeUnless { it.toMap().isEmpty() }

    companion object {
        /** [value] as a node key: 64-hex, lowercased; null when it is not one. */
        fun normalizedHex(value: String?): String? {
            if (value == null || value.length != 64) return null
            val lower = if (value.any { it in 'A'..'F' }) value.lowercase() else value
            return lower.takeIf { isCanonicalHex64(it) }
        }

        /** [value] as an address key ([canonicalAddress]), its pubkey lowercased; null when it is not one. */
        fun normalizedAddress(value: String?): String? {
            if (value == null) return null
            val firstColon = value.indexOf(':')
            if (firstColon !in 1..5 || value.length < firstColon + 66) return canonicalAddress(value)
            val pubkey = value.substring(firstColon + 1, firstColon + 65)
            if (pubkey.none { it in 'A'..'F' }) return canonicalAddress(value)
            return canonicalAddress(value.substring(0, firstColon + 1) + pubkey.lowercase() + value.substring(firstColon + 65))
        }
    }
}

/** Builds one event's links. */
inline fun links(block: LinkBuilder.() -> Unit): List<Link<*>> = LinkBuilder().apply(block).build()

/**
 * Every tag [parse] accepts, handed to [block]. The way a mapper walks its tags: Quartz's Tag
 * class parser says which tags are its own and what they hold, e.g.
 * `each(event.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }`. A parser that
 * throws on a malformed tag drops that tag only.
 */
inline fun <T : Any> LinkBuilder.each(
    tags: TagArray,
    parse: (Array<String>) -> T?,
    block: LinkBuilder.(T) -> Unit,
) {
    for (tag in tags) {
        val parsed = runCatching { parse(tag) }.getOrNull() ?: continue
        block(parsed)
    }
}
