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
package com.vitorpamplona.neo4j.eventstore.sim

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import java.security.MessageDigest
import kotlin.random.Random

/**
 * A deterministic random stream of graph-relevant events (spec plan P3): NIP-10 threads,
 * comments, reactions, reposts with embedded originals, zaps with embedded requests, follow and
 * mute lists, profiles, articles and their addresses, reports, NIP-85 cards and provider lists,
 * content `nostr:` links (including a pasted nsec), and a kind Quartz does not know.
 *
 * Events reference earlier ids, users and addresses, so the graph is connected and stubs occur
 * naturally (a reference to something not held).
 */
class GraphCorpus(
    seed: Int,
    userCount: Int = 12,
) {
    private val random = Random(seed)
    private var clock = 1_700_000_000L
    private var counter = 0

    val users = (0 until userCount).map { hex("user$it") }
    private val ids = ArrayList<String>()
    private val addresses = ArrayList<String>()

    fun next(): Event {
        clock += random.nextLong(0, 3)
        val author = users.random(random)
        val kind = KINDS.random(random)
        val tags = ArrayList<List<String>>()
        var content = ""
        when (kind) {
            1 -> {
                if (ids.isNotEmpty() && random.nextBoolean()) {
                    val root = ids.random(random)
                    if (random.nextBoolean()) {
                        tags += listOf("e", root, "", "root")
                        tags += listOf("e", ids.random(random), "", "reply")
                    } else {
                        tags += listOf("e", root)
                    }
                }
                repeat(random.nextInt(0, 3)) { tags += listOf("p", users.random(random)) }
                if (random.nextInt(4) == 0) tags += listOf("t", listOf("nostr", "bitcoin", "art").random(random))
                if (random.nextInt(5) == 0 && ids.isNotEmpty()) tags += listOf("q", ids.random(random))
                if (random.nextInt(6) == 0 && addresses.isNotEmpty()) tags += listOf("q", addresses.random(random))
                content = "note ${counter++}"
                if (random.nextInt(4) == 0) content += " nostr:${users.random(random).hexToByteArray().toNpub()}"
                if (random.nextInt(15) == 0) content += " nostr:${hex("secret$counter").hexToByteArray().toNsec()}"
            }

            7 -> {
                if (ids.isNotEmpty()) tags += listOf("e", ids.random(random))
                tags += listOf("p", users.random(random))
                content = listOf("+", "-", "🤙").random(random)
            }

            3, 10000 -> {
                repeat(random.nextInt(0, 6)) { tags += listOf("p", users.random(random)) }
            }

            0 -> {
                content = """{"name":"n${random.nextInt(5)}","nip05":"u${random.nextInt(5)}@x.com"}"""
            }

            30023 -> {
                tags += listOf("d", "post${random.nextInt(3)}")
                tags += listOf("title", "T${random.nextInt(9)}")
                if (addresses.isNotEmpty() && random.nextBoolean()) tags += listOf("a", addresses.random(random))
            }

            1111 -> {
                if (ids.isNotEmpty()) {
                    tags += listOf("E", ids.random(random), "", users.random(random))
                    tags += listOf("K", "1")
                    tags += listOf("e", ids.random(random), "", users.random(random))
                    tags += listOf("k", "1111")
                }
                content = "comment ${counter++}"
            }

            6 -> {
                if (ids.isNotEmpty() && random.nextBoolean()) tags += listOf("e", ids.random(random))
                content = Event(hex("orig${counter++}"), users.random(random), clock - 10, 1, emptyArray(), "orig", SIG).toJson()
            }

            9735 -> {
                val request =
                    Event(
                        hex("req${counter++}"),
                        users.random(random),
                        clock,
                        9734,
                        arrayOf(arrayOf("p", users.random(random)), arrayOf("amount", "21000")),
                        "",
                        SIG,
                    )
                tags += listOf("p", request.tags[0][1])
                if (ids.isNotEmpty()) tags += listOf("e", ids.random(random))
                tags += listOf("description", request.toJson())
            }

            1984 -> {
                tags += listOf("p", users.random(random), listOf("spam", "impersonation").random(random))
            }

            30382 -> {
                tags += listOf("d", users.random(random))
                tags += listOf("rank", random.nextInt(0, 100).toString())
            }

            10040 -> {
                tags += listOf("30382:rank", users.random(random), "wss://scores.example")
            }

            else -> {
                if (ids.isNotEmpty()) tags += listOf("e", ids.random(random))
                tags += listOf("p", users.random(random))
            }
        }
        val id = hex("event${counter++}|$kind|$author|$clock|$tags|$content")
        val event = Event(id, author, clock, kind, tags.map { it.toTypedArray() }.toTypedArray(), content, SIG)
        ids += id
        if (kind == 30023) addresses += "30023:$author:" + tags.first { it[0] == "d" }[1]
        return event
    }

    companion object {
        val SIG = "f".repeat(128)
        val KINDS = listOf(1, 1, 1, 1, 7, 7, 3, 10000, 0, 30023, 1111, 6, 9735, 1984, 30382, 10040, 12_345)

        fun hex(seed: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(seed.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
