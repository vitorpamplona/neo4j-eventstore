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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.tags.isIndexableTagName
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.tags.ServiceProviderTag

/**
 * Links Quartz's hint providers miss or get wrong (spec §5.3). Each gap is also filed upstream;
 * the projection shields itself meanwhile.
 */
object LinkRules {
    const val VIA_CONTENT = "content"
    const val VIA_DESCRIPTION = "description"
    const val VIA_EMBEDDED = "embedded"

    /** A reference the providers do not name: family (e/p/a), target, and where it came from. */
    data class ExtraLink(
        val family: Char,
        val target: NodeRef,
        val via: String,
        val roles: List<String>? = null,
    )

    /**
     * Hex keys pasted into [content] as `nsec1…`. Quartz's `ListEntityExt.pubKeys()` maps an
     * NSec to its hex — a PRIVATE KEY — so every class using `citedNIP19()` reports a pasted
     * nsec as a "linked pubkey". The deriver subtracts these before anything is written: a
     * security rule, not a nicety, and pinned by an invariant test.
     */
    fun contentSecrets(content: String): Set<String> {
        // Any case: Quartz's parser accepts `Nsec1…` (a keyboard's auto-capital) and `NSEC1…`.
        if (!carriesNsec(content)) return emptySet()
        return runCatching {
            Nip19Parser.parseAll(content).filterIsInstance<NSec>().mapTo(HashSet()) { it.hex.lowercase() }
        }.getOrDefault(emptySet())
    }

    /** Whether [value] contains a bech32 private-key prefix, in any case. */
    fun carriesNsec(value: String): Boolean = value.contains("nsec1", ignoreCase = true)

    /**
     * value → the multi-letter tag name that carried it (`zap`, `pinned`, `exercise`, …), for the
     * `via` of a derived edge. The first such tag wins; anything a provider names that no tag
     * carries came from the content (`nostr:` URIs).
     */
    fun multiLetterVias(tags: Array<Array<String>>): Map<String, String> {
        val out = HashMap<String, String>()
        for (tag in tags) {
            if (tag.size < 2 || isIndexableTagName(tag[0])) continue
            val value = tag[1]
            out.putIfAbsent(value, tag[0])
            canonicalAddress(value)?.let { out.putIfAbsent(it, tag[0]) }
        }
        return out
    }

    fun extraLinks(
        event: Event,
        literalEvents: Set<String>,
        literalUsers: Set<String>,
        literalUppercaseP: Set<String>,
        secrets: Set<String>,
    ): List<ExtraLink> {
        val out = ArrayList<ExtraLink>()
        when (event) {
            // The zap SENDER lives in the receipt's embedded request (the `description` tag);
            // the receipt's providers report only the recipient. A literal `P` already says it.
            is ZapReceiptEvent -> {
                val request = runCatching { event.zapRequest }.getOrNull()
                if (request != null) {
                    val sender = request.pubKey
                    if (isCanonicalHex64(sender) && sender !in literalUppercaseP && sender !in secrets) {
                        out += ExtraLink('p', NodeRef(NodeKind.USER, sender), VIA_DESCRIPTION, listOf(RoleTable.ZAPPER))
                    }
                    if (isCanonicalHex64(request.id) && request.id != event.id && request.id !in literalEvents) {
                        out += ExtraLink('e', NodeRef(NodeKind.EVENT, request.id), VIA_DESCRIPTION)
                    }
                }
            }

            // Reposts often embed the original as JSON content with no tag naming it.
            is RepostEvent, is GenericRepostEvent -> {
                val inner =
                    runCatching {
                        when (event) {
                            is RepostEvent -> event.containedPost()
                            is GenericRepostEvent -> event.containedPost()
                            else -> null
                        }
                    }.getOrNull()
                if (inner != null) {
                    if (isCanonicalHex64(inner.id) && inner.id != event.id && inner.id !in literalEvents) {
                        out += ExtraLink('e', NodeRef(NodeKind.EVENT, inner.id), VIA_EMBEDDED)
                    }
                    if (isCanonicalHex64(inner.pubKey) && inner.pubKey !in literalUsers) {
                        out += ExtraLink('p', NodeRef(NodeKind.USER, inner.pubKey), VIA_EMBEDDED)
                    }
                }
            }
        }
        // Kind 10040 names its trust services in multi-letter "<kind>:<type>" tags and implements
        // no provider; the service pubkey is the whole point of the list.
        if (event.kind == 10040) {
            for (tag in event.tags) {
                val service = runCatching { ServiceProviderTag.parse(tag) }.getOrNull() ?: continue
                if (isCanonicalHex64(service.pubkey)) {
                    out += ExtraLink('p', NodeRef(NodeKind.USER, service.pubkey), tag[0])
                }
            }
        }
        return out
    }
}
