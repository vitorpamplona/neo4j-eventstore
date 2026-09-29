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
package com.vitorpamplona.neo4j.eventstore.engine

import com.vitorpamplona.quartz.nip01Core.core.Event
import java.security.MessageDigest

/** Test events: plain (untyped) Quartz events with deterministic fake ids — the deriver re-types them. */
object Fixtures {
    val SIG = "f".repeat(128)

    fun hex(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    val ALICE = hex("alice")
    val BOB = hex("bob")
    val CAROL = hex("carol")

    fun event(
        kind: Int,
        pubkey: String = ALICE,
        tags: List<List<String>> = emptyList(),
        content: String = "",
        createdAt: Long = 1_700_000_000,
        id: String = hex("$kind|$pubkey|$tags|$content|$createdAt"),
    ) = Event(id, pubkey, createdAt, kind, tags.map { it.toTypedArray() }.toTypedArray(), content, SIG)
}
