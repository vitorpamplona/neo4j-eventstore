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
package com.vitorpamplona.neo4j.eventstore.engine.schema

import com.vitorpamplona.quartz.nip01Core.core.Address
import java.security.MessageDigest

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

/**
 * The canonical address id for a RAW coordinate [value] (as a tag or an event writes it). Not
 * idempotent: a key already bounded by [boundedD] would be hashed again, so apply it once, at the
 * edge of the graph ([com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder]).
 *
 * The canonical address id for [value], or null if it is not `kind:hexpubkey:d` with a kind in
 * 0..65535 and a lowercase 64-hex pubkey.
 *
 * Parsed here rather than by `Address.parse`, which logs a warning for every string of 66+
 * characters that is not an address — and this runs on every multi-letter tag value (a zap
 * receipt's whole `description`, every `imeta`) at ingest rate. Same result: `d` is everything
 * after the second ':', and the kind is normalized through Int.
 */
fun canonicalAddress(value: String): String? {
    val c1 = value.indexOf(':')
    if (c1 !in 1..5) return null
    for (i in 0 until c1) if (value[i] !in '0'..'9') return null
    val c2 = c1 + 65
    if (value.length <= c2 || value[c2] != ':') return null
    val pubkey = value.substring(c1 + 1, c2)
    if (!isCanonicalHex64(pubkey)) return null
    val kind = value.substring(0, c1).toInt()
    if (kind > MAX_KIND) return null
    return assembleAddress(kind, pubkey, value.substring(c2 + 1))
}

const val MAX_KIND = 65535

/** A `d` longer than this many UTF-8 bytes joins the graph by its hash (see [boundedD]). */
const val MAX_ADDRESS_D_BYTES = 1024

const val LONG_D_PREFIX = "sha256:"

/**
 * [d] as it appears in an address key. An `:Address` key sits behind a uniqueness constraint,
 * and Neo4j refuses to index a value over ~8 KB — so an unbounded `d` (anyone can publish one, or
 * tag one) would make its event's transaction fail on every retry and every reconcile. A long
 * `d` is replaced by `sha256:<hex of its UTF-8>`: still one key per distinct `d`, so the slot rule
 * holds; the `d` text itself is not kept.
 *
 * A `d` that already starts with [LONG_D_PREFIX] is hashed too, whatever its length: otherwise
 * `d = "sha256:" + sha256(X)` and a long `d = X` would share one key, and two slots Vespa keeps
 * apart would collapse into one here (each reconcile then displacing the other).
 */
fun boundedD(d: String): String {
    if (d.startsWith(LONG_D_PREFIX)) return hashedD(d)
    if (d.length * 3 <= MAX_ADDRESS_D_BYTES) return d
    if (d.encodeToByteArray().size <= MAX_ADDRESS_D_BYTES) return d
    return hashedD(d)
}

/** `sha256:<hex of [d]'s UTF-8>`: a `d` as a key without its text. */
fun hashedD(d: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(d.encodeToByteArray())
    return LONG_D_PREFIX + digest.joinToString("") { ((it.toInt() and 0xff) or 0x100).toString(16).substring(1) }
}

/** The one way an address key is built: `kind:pubkey:` + [boundedD]. */
fun assembleAddress(
    kind: Int,
    pubkey: String,
    d: String,
): String = Address.assemble(kind, pubkey, boundedD(d))

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
