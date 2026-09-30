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
package com.vitorpamplona.neo4j.eventstore.benchmark

import com.vitorpamplona.neo4j.eventstore.cypher.CypherRequest
import com.vitorpamplona.neo4j.eventstore.cypher.CypherService
import com.vitorpamplona.neo4j.eventstore.cypher.Hydrator
import com.vitorpamplona.neo4j.eventstore.engine.client.Neo4jGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.SIG
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus.Companion.hex
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The showcase queries of `docs/schema.md` ("Showcase: what meaning-typed relations buy you"),
 * read FROM that file and run through the real [CypherService] against a graph whose answers are
 * known by construction. A query edited in the docs is the query tested here.
 */
@Tag("integration")
class ShowcaseQueriesIT {
    private var t = 1_700_000_000L

    private fun ev(
        kind: Int,
        author: String,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ): Event {
        t += 1
        return Event(hex("$kind|$author|$tags|$content|$t"), author, t, kind, tags.map { it.toTypedArray() }.toTypedArray(), content, SIG)
    }

    private val me = hex("me")
    private val f1 = hex("f1")
    private val f2 = hex("f2")
    private val fan = hex("fan")
    private val x = hex("x")
    private val r1 = hex("r1")
    private val r2 = hex("r2")
    private val r3 = hex("r3")
    private val suspect = hex("suspect")
    private val provider = hex("provider")
    private val labeler = hex("labeler")
    private val issuer = hex("issuer")
    private val mod = hex("mod")
    private val owner = hex("owner")
    private val org = hex("org")

    private val article = "30023:$x:post"
    private val badge = "30009:$issuer:bravery"
    private val community = "34550:$owner:com"
    private val party = "31922:$org:party"
    private val isbn = "isbn:9780141439518"

    /** Queries by label, as the docs write them. */
    private fun showcase(): Map<String, String> {
        val doc = listOf(File("docs/schema.md"), File("../docs/schema.md")).first { it.isFile }.readText()
        val section = doc.substringAfter("## Showcase:").substringAfter("```cypher").substringBefore("```")
        val out = LinkedHashMap<String, String>()
        Regex("""(?m)^// (S\d+) — """).findAll(section).toList().let { heads ->
            heads.forEachIndexed { i, m ->
                val end = if (i + 1 < heads.size) heads[i + 1].range.first else section.length
                out[m.groupValues[1]] = section.substring(m.range.first, end).trim().removeSuffix(";")
            }
        }
        return out
    }

    private suspend fun rows(
        cypher: CypherService,
        query: String,
        params: Map<String, Any?>,
    ): JsonArray {
        val (outcome, json) = cypher.query(CypherRequest(query, params, hydrate = false))
        assertTrue(outcome is CypherService.Outcome.Ok, "$query -> $outcome")
        return Json.parseToJsonElement(json).jsonObject["rows"]!!.jsonArray
    }

    private fun JsonElement.cell(i: Int) = jsonArray[i]

    private fun JsonElement.str(i: Int) = cell(i).jsonPrimitive.content

    private fun JsonElement.num(i: Int) = cell(i).jsonPrimitive.long

    @Test
    fun theShowcaseQueriesAnswerAsDocumented(): Unit =
        runBlocking {
            val queries = showcase()
            assertEquals((1..17).map { "S$it" }, queries.keys.toList(), "the docs' showcase labels")

            // The social graph: I follow f1, f2 and fan; they follow x, the suspect and r1–r3.
            val note = ev(1, me, content = "N")
            val c1 = ev(1, r1, listOf(listOf("e", note.id, "", "root"), listOf("e", note.id, "", "reply")), "c1")
            val c2 = ev(1, r2, listOf(listOf("e", note.id, "", "root"), listOf("e", c1.id, "", "reply")), "c2")
            val c3 = ev(1, r3, listOf(listOf("e", note.id, "", "root"), listOf("e", c2.id, "", "reply")), "c3")
            val d1 = ev(1, f1, listOf(listOf("e", note.id, "", "root")), "d1")
            val d2 = ev(1, f2, listOf(listOf("e", note.id, "", "root")), "d2")
            val deletedPost = hex("a post its author deleted")
            val award = ev(8, issuer, listOf(listOf("a", badge), listOf("p", me), listOf("p", fan)))
            val events =
                listOf(
                    ev(3, me, listOf(listOf("p", f1), listOf("p", f2), listOf("p", fan))),
                    ev(3, f1, listOf(listOf("p", x), listOf("p", r1), listOf("p", me))),
                    ev(3, f2, listOf(listOf("p", x), listOf("p", r2))),
                    ev(3, fan, listOf(listOf("p", r3))),
                    note,
                    c1,
                    c2,
                    c3,
                    d1,
                    d2,
                    // Reactions to my note, and my trust provider's rank of one reactor.
                    ev(7, r1, listOf(listOf("e", note.id), listOf("p", me)), "+"),
                    ev(7, fan, listOf(listOf("e", note.id), listOf("p", me)), "🤙"),
                    ev(10040, me, listOf(listOf("30382:rank", provider, "wss://scores.example/"))),
                    ev(30382, provider, listOf(listOf("d", r1), listOf("rank", "90"))),
                    // A zap from fan (the receipt copies the sender into `P`).
                    ev(9735, hex("lnurl"), listOf(listOf("p", me), listOf("P", fan), listOf("e", note.id))),
                    // r1–r3 (my follows' follows) report the suspect as a person.
                    ev(1984, r1, listOf(listOf("p", suspect, "impersonation"))),
                    ev(1984, r2, listOf(listOf("p", suspect, "impersonation"))),
                    ev(1984, r3, listOf(listOf("p", suspect, "impersonation"))),
                    // f2 tags me AND cites me in the text.
                    ev(1, f2, listOf(listOf("p", me)), "hey nostr:${me.hexToByteArray().toNpub()}"),
                    // The article and what the network did with it.
                    ev(30023, x, listOf(listOf("d", "post"), listOf("title", "Post"))),
                    ev(1111, r1, listOf(listOf("A", article), listOf("K", "30023"), listOf("a", article), listOf("k", "30023")), "nice"),
                    ev(7, r2, listOf(listOf("a", article), listOf("p", x)), "+"),
                    ev(1, r3, listOf(listOf("q", article)), "read this"),
                    ev(
                        9802,
                        f1,
                        listOf(listOf("a", article), listOf("p", x, "", "author")),
                        "as nostr:${x.hexToByteArray().toNpub()} said",
                    ),
                    // A label from a labeler I trust.
                    ev(1985, labeler, listOf(listOf("L", "ugc"), listOf("l", "nsfw", "ugc"), listOf("e", note.id))),
                    // A badge, awarded to me and fan; only I wear it.
                    award,
                    ev(30008, me, listOf(listOf("d", "profile_badges"), listOf("a", badge), listOf("e", award.id))),
                    // A community, its moderator, one approval.
                    ev(34550, owner, listOf(listOf("d", "com"), listOf("p", mod, "", "moderator"))),
                    ev(4550, mod, listOf(listOf("a", community), listOf("e", d1.id), listOf("p", f1), listOf("k", "1"))),
                    // NIP-22 comments about a book.
                    ev(1111, r1, listOf(listOf("I", isbn), listOf("K", "isbn"), listOf("i", isbn), listOf("k", "isbn")), "great book"),
                    ev(1111, r2, listOf(listOf("I", isbn), listOf("K", "isbn"), listOf("i", isbn), listOf("k", "isbn")), "agreed"),
                    // A deleted post (never held) with a reply left behind.
                    ev(5, fan, listOf(listOf("e", deletedPost))),
                    ev(1, r1, listOf(listOf("e", deletedPost, "", "root")), "orphan"),
                    // RSVPs to a party.
                    ev(31925, me, listOf(listOf("d", "rsvp1"), listOf("a", party), listOf("status", "accepted"))),
                    ev(31925, fan, listOf(listOf("d", "rsvp2"), listOf("a", party), listOf("status", "accepted"))),
                    ev(31925, r1, listOf(listOf("d", "rsvp3"), listOf("a", party), listOf("status", "declined"))),
                )

            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            Neo4jGraphIndex(driver).apply(events)
            val cypher = CypherService(driver, hydrator = Hydrator { emptyList() })
            val params =
                mapOf(
                    "me" to me,
                    "since" to 0L,
                    "article" to article,
                    "id" to note.id,
                    "note" to note.id,
                    "labeler" to labeler,
                    "badge" to badge,
                    "community" to community,
                    "externalId" to isbn,
                    "calendarEvent" to party,
                    "from" to me,
                    "to" to x,
                    "ids" to listOf(note.id),
                )

            suspend fun q(label: String) = rows(cypher, queries.getValue(label), params)

            // S1: one follower (f1), one zap received, two reactions, two direct replies to me… as edges.
            val s1 = q("S1")[0]
            assertEquals(listOf(1L, 0L, 0L, 0L, 1L, 0L, 2L), (0..6).map { s1.num(it) }, "S1 $s1")
            assertEquals(1L, s1.num(8), "S1 badges $s1")

            val s2 = q("S2").map { it.str(0) }.toSet()
            assertTrue(s2.containsAll(setOf("ROOT", "PARENT", "REACTED", "QUOTE", "HIGHLIGHTED")), "S2 $s2")

            val s3 = q("S3").associate { it.str(0) to it.num(2) }
            assertEquals(1L, s3["p"], "S3 $s3")
            assertEquals(1L, s3["content"], "S3 $s3")

            val s4 = q("S4")
            assertEquals(1, s4.size, "S4 $s4")
            assertEquals(listOf(5L, 5L), listOf(s4[0].num(1), s4[0].num(2)), "S4 $s4")

            val s5 = q("S5")
            assertEquals(3L, s5[0].num(1), "S5 deepest $s5")
            assertEquals(c3.id, s5[0].str(0), "S5 $s5")

            val s6 = q("S6").associate { it.str(0) to it.num(2) }
            assertEquals(mapOf("+" to 90L, "🤙" to 0L), s6, "S6")

            val s7 = q("S7")
            assertEquals(1, s7.size, "S7 $s7")
            assertEquals(1L, s7[0].num(1))
            assertEquals("true", s7[0].str(3), "S7 I follow fan")

            val s8 = q("S8")
            assertEquals(1, s8.size, "S8 $s8")
            assertEquals(listOf("impersonation", "3"), listOf(s8[0].str(1), s8[0].str(2)), "S8 $s8")

            val s9 = q("S9")
            assertEquals(listOf("ugc:nsfw"), s9[0].cell(1).jsonArray.map { it.jsonPrimitive.content }, "S9 $s9")

            val s10 = q("S10").map { it.cell(1).jsonPrimitive.content }
            assertEquals(listOf("true", "false"), s10, "S10: I wear it, fan does not")

            val s11 = q("S11")
            assertEquals(listOf(1L, 1L), listOf(s11[0].num(1), s11[0].num(2)), "S11 $s11")

            val s12 = q("S12").associate { it.str(0) to it.num(3) }
            assertEquals(2L, s12["ROOT"], "S12 $s12")

            val s13 = q("S13")
            assertEquals(1L, s13[0].num(1), "S13 $s13")
            assertEquals(1, s13[0].cell(2).jsonArray.size, "S13 cites x $s13")

            val s14 = q("S14")
            assertEquals(listOf(deletedPost to 1L), s14.map { it.str(0) to it.num(1) }, "S14")

            val s15 = q("S15").associate { it.str(0) to (it.num(1) to it.num(2)) }
            assertEquals(mapOf("accepted" to (2L to 1L), "declined" to (1L to 0L)), s15, "S15")

            val s16 = q("S16")
            assertEquals(2L, s16[0].num(0), "S16 $s16")

            val s17 = q("S17")[0]
            assertEquals(listOf(2L, 0L, 0L, 3L, 5L, 1L), (1..6).map { s17.num(it) }, "S17 $s17")
            driver.close()
        }
}
