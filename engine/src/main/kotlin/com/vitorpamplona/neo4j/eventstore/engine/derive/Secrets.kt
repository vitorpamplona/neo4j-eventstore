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

import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec

/**
 * The nsec rule: a PRIVATE key never becomes a node or a property, whatever path it took.
 *
 * People paste `nsec1…` into notes by mistake. Quartz's `ListEntityExt.pubKeys()` has mapped an
 * NSec to its hex as a "linked pubkey" before, and a tag value or a prop can carry one verbatim;
 * the deriver drops any link whose target or props carry either form. A security rule, not a
 * nicety, and pinned by an invariant test.
 */
object Secrets {
    /** Hex keys pasted into [content] as `nsec1…`, in any case (Quartz accepts `Nsec1…` and `NSEC1…`). */
    fun inContent(content: String): Set<String> {
        if (!carriesNsec(content)) return emptySet()
        return runCatching {
            Nip19Parser.parseAll(content).filterIsInstance<NSec>().mapTo(HashSet()) { it.hex.lowercase() }
        }.getOrDefault(emptySet())
    }

    /** Whether [value] contains a bech32 private-key prefix, in any case. */
    fun carriesNsec(value: String): Boolean = value.contains("nsec1", ignoreCase = true)

    /** Whether [value] carries a private key: a bech32 one, or one of the [secrets] hex. */
    fun leaks(
        value: String,
        secrets: Set<String>,
    ): Boolean {
        if (carriesNsec(value)) return true
        if (secrets.isEmpty()) return false
        // Secrets are lowercase hex; keys are lowercase by now, free text may not be.
        for (secret in secrets) if (value.contains(secret, ignoreCase = true)) return true
        return false
    }
}
