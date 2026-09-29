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

import com.vitorpamplona.quartz.nip01Core.core.Address

/**
 * Canonical lowercase 64-hex — the only form an id or pubkey joins the graph in. Quartz's tag
 * parsers check LENGTH only; an uppercase or malformed value would otherwise mint a second node
 * for the same key (or a node for garbage).
 */
fun isCanonicalHex64(value: String): Boolean {
    if (value.length != 64) return false
    for (c in value) if (c !in '0'..'9' && c !in 'a'..'f') return false
    return true
}

/** The canonical address id for [value], or null if it does not parse as `kind:hexpubkey:d`. */
fun canonicalAddress(value: String): String? {
    if (value.indexOf(':') < 0) return null
    val parsed = runCatching { Address.parse(value) }.getOrNull() ?: return null
    if (!isCanonicalHex64(parsed.pubKeyHex)) return null
    return parsed.toValue()
}

/** [value] cut to at most [maxBytes] UTF-8 bytes without splitting a code point. */
fun truncateUtf8(
    value: String,
    maxBytes: Int,
): String {
    if (value.length * 3 <= maxBytes) return value
    val bytes = value.encodeToByteArray()
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    // Back off continuation bytes (10xxxxxx) so the cut lands on a code-point boundary.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.decodeToString(0, end)
}
