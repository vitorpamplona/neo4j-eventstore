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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.neo4j.driver.summary.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The example queries of spec §8.7 — the ones docs/schema.md publishes — run through the real
 * [CypherService] against a small graph whose answers are known by construction. A schema change
 * that breaks a documented query breaks this test.
 */
@Tag("integration")
class ReferenceQueriesIT {
    private val me = hex("me")
    private val f1 = hex("follow1")
    private val f2 = hex("follow2")
    private val x = hex("x")
    private val y = hex("y")
    private val service = hex("service")
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

    private val n1 = ev(1, x, listOf(listOf("t", "bitcoin"), listOf("t", "nostr")), "N1")
    private val n2 = ev(1, y, listOf(listOf("t", "bitcoin"), listOf("t", "art")), "N2")
    private val r1 = ev(1, f1, listOf(listOf("e", n1.id, "", "root")), "R1 replies to N1")
    private val r2 = ev(1, f2, listOf(listOf("e", n1.id, "", "root"), listOf("e", r1.id, "", "reply")), "R2 replies to R1")
    private val zapRequest = ev(9734, f1, listOf(listOf("p", x), listOf("e", n1.id)))

    // NIP-57: the receipt copies the request's pubkey into `P`, the zap sender.
    private val zapReceipt =
        ev(9735, hex("lnurl"), listOf(listOf("p", x), listOf("P", f1), listOf("e", n1.id), listOf("description", zapRequest.toJson())))
    private val all =
        listOf(
            ev(3, me, listOf(listOf("p", f1), listOf("p", f2))),
            ev(3, f1, listOf(listOf("p", x), listOf("p", y), listOf("p", me))),
            ev(3, f2, listOf(listOf("p", x))),
            n1,
            n2,
            r1,
            r2,
            zapReceipt,
            ev(7, f2, listOf(listOf("e", n2.id), listOf("p", y)), "+"),
            ev(30382, service, listOf(listOf("d", x), listOf("rank", "91"))),
            ev(30382, service, listOf(listOf("d", y), listOf("rank", "40"))),
            // Reports: a standing one about x, one about x's NOTE with an invented type, and a
            // localized label about y.
            ev(1984, f1, listOf(listOf("p", x, "impersonation"))),
            ev(1984, f2, listOf(listOf("e", n1.id, "swearing"), listOf("p", x))),
            ev(1984, me, listOf(listOf("p", y, "Spam \uD83D\uDCE3"))),
        )

    private suspend fun rows(
        cypher: CypherService,
        query: String,
        params: Map<String, Any?> = emptyMap(),
    ): JsonArray {
        val (outcome, json) = cypher.query(CypherRequest(query, params))
        assertTrue(outcome is CypherService.Outcome.Ok, "$query -> $outcome")
        return Json.parseToJsonElement(json).jsonObject["rows"]!!.jsonArray
    }

    @Test
    fun theDocumentedQueriesAnswerAsDocumented() =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(GraphPolicy.Default)
            Neo4jGraphIndex(driver).apply(all)
            val byId = all.associateBy { it.id }
            val cypher = CypherService(driver, hydrator = Hydrator { ids -> ids.mapNotNull { byId[it] } })

            // T1 — follower count, O(1) from the dense-node group.
            val t1 = rows(cypher, "MATCH (u:User {pubkey: \$pk}) RETURN COUNT { (u)<-[:FOLLOW]-() } AS followers", mapOf("pk" to x))
            assertEquals(2L, t1[0].jsonArray[0].jsonPrimitive.long)

            // T2 — follows-of-follows I don't follow, ranked by how many follows follow them.
            val t2 =
                rows(
                    cypher,
                    """
                    MATCH (:Address {id: '3:' + ${'$'}me + ':'})<-[:ADDRESS]-(mine:Data)-[:FOLLOW]->(f:User)
                    MATCH (:Address {id: '3:' + f.pubkey + ':'})<-[:ADDRESS]-(:Data)-[:FOLLOW]->(fof:User)
                    WHERE fof.pubkey <> ${'$'}me AND NOT EXISTS { (mine)-[:FOLLOW]->(fof) }
                    RETURN fof.pubkey AS pk, count(DISTINCT f) AS via ORDER BY via DESC, pk
                    """.trimIndent(),
                    mapOf("me" to me),
                )
            assertEquals(listOf(x to 2L, y to 1L), t2.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.long })

            // T3 — the whole thread under a root (every reply tags the root), hydrated from the source.
            val t3 =
                rows(
                    cypher,
                    "MATCH (root:Event {id: \$id})<-[:ROOT]-(n:Data) RETURN n ORDER BY n.created_at",
                    mapOf("id" to n1.id),
                )
            assertEquals(
                listOf("R1 replies to N1", "R2 replies to R1"),
                t3.map {
                    it.jsonArray[0]
                        .jsonObject["content"]!!
                        .jsonPrimitive.content
                },
            )

            // T3b — the reply TREE through PARENT edges; a direct reply to the root counts.
            val t3b =
                rows(
                    cypher,
                    "MATCH (root:Event {id: \$id}) ((p)<-[:PARENT]-(c:Data))+ (leaf) RETURN leaf.id AS id",
                    mapOf("id" to n1.id),
                )
            assertEquals(setOf(r1.id, r2.id), t3b.map { it.jsonArray[0].jsonPrimitive.content }.toSet())

            // T5 — notes a user zapped, through the receipts that name them as the sender.
            val t5 =
                rows(
                    cypher,
                    """
                    MATCH (zapper:User {pubkey: ${'$'}zapper})<-[:ZAP_SENDER]-(z:Data)-[:ZAPPED]->(n:Data {kind: 1})
                    RETURN n.id AS id, count(z) AS zaps
                    """.trimIndent(),
                    mapOf("zapper" to f1),
                )
            assertEquals(listOf(n1.id), t5.map { it.jsonArray[0].jsonPrimitive.content })

            // T9 — who NIP-85 service S ranks >= 80.
            val t9 =
                rows(
                    cypher,
                    "MATCH (:User {pubkey: \$s})<-[:AUTHOR]-(:Data {kind: 30382})-[a:SUBJECT]->(u:User) WHERE a.rank >= 80 RETURN u.pubkey AS pk, a.rank AS rank",
                    mapOf("s" to service),
                )
            assertEquals(listOf(x to 91L), t9.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.long })

            // T11 — hashtags used alongside #bitcoin.
            val t11 =
                rows(
                    cypher,
                    "MATCH (:Tag {key: 't:bitcoin'})<-[:HASHTAG]-(n:Data)-[:HASHTAG]->(o:Tag) WHERE o.key <> 't:bitcoin' RETURN o.value AS tag ORDER BY tag",
                )
            assertEquals(listOf("art", "nostr"), t11.map { it.jsonArray[0].jsonPrimitive.content })

            // Hybrid — ids from a (Vespa) search, then the graph.
            val hybrid =
                rows(
                    cypher,
                    "UNWIND \$ids AS id MATCH (n:Event:Data {id: id})<-[:REACTED]-(:Data)-[:AUTHOR]->(r:User) RETURN n.id AS id, count(DISTINCT r) AS reactors",
                    mapOf("ids" to listOf(n1.id, n2.id)),
                )
            assertEquals(listOf(n2.id to 1L), hybrid.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.long })

            // T12 — user-wide reports of x (not reports of x's notes), by type.
            val t12 =
                rows(
                    cypher,
                    """
                    MATCH (:User {pubkey: ${'$'}x})<-[r:REPORTED_USER]-(:Data)-[:AUTHOR]->(reporter:User)
                    RETURN reporter.pubkey AS pk, r.report AS type
                    """.trimIndent(),
                    mapOf("x" to x),
                )
            assertEquals(
                listOf(f1 to "impersonation"),
                t12.map {
                    it.jsonArray[0].jsonPrimitive.content to
                        it.jsonArray[1].jsonPrimitive.content
                },
            )

            // Reports that count, whatever they are about: the standard categories only, so the
            // invented "swearing" (category `other`) drops out — and it is still findable by its text.
            val serious =
                rows(
                    cypher,
                    """
                    MATCH (u:User)<-[r:REPORTED_USER|REPORTED_AUTHOR]-(:Data)
                    WHERE r.report IN ['impersonation', 'spam', 'illegal', 'malware']
                    RETURN u.pubkey AS pk, collect(r.report) AS types ORDER BY pk
                    """.trimIndent(),
                )
            assertEquals(
                setOf(x to listOf("impersonation"), y to listOf("spam")),
                serious
                    .map {
                        it.jsonArray[0].jsonPrimitive.content to
                            it.jsonArray[1].jsonArray.map { t ->
                                t.jsonPrimitive.content
                            }
                    }.toSet(),
            )
            val swearing = rows(cypher, "MATCH ()-[r:REPORTED {report_raw: 'swearing'}]->(n:Data) RETURN n.id AS id")
            assertEquals(listOf(n1.id), swearing.map { it.jsonArray[0].jsonPrimitive.content })

            // A report query not anchored on one user seeks the relationship index, not every edge.
            fun operators(p: Plan): List<String> = listOf(p.operatorType()) + p.children().flatMap { operators(it) }
            val plan =
                driver.session().use { s ->
                    operators(s.run("EXPLAIN MATCH ()-[r:REPORTED_USER {report: 'impersonation'}]->(u) RETURN u").consume().plan())
                }
            assertTrue(plan.any { "RelationshipIndexSeek" in it }, "$plan")

            // JSON has no NaN: a non-finite result must not break the streamed document.
            val nan = rows(cypher, "RETURN 0.0 / 0.0 AS x, 1.0 / 0.0 AS y")
            assertEquals(listOf("NaN", "Infinity"), nan[0].jsonArray.map { it.jsonPrimitive.content })

            // A refused query is audited even though execute() never runs it.
            val audited = ArrayList<String>()
            val auditing = CypherService(driver, audit = { audited += it.outcome })
            assertTrue(auditing.precheck(CypherRequest("CREATE (:Pwned)"), caller = "x") != null)
            assertTrue(audited.single().startsWith("rejected"), "$audited")

            // Without hydration a held event is its node properties.
            val (_, raw) = cypher.query(CypherRequest("MATCH (n:Event:Data {id: \$id}) RETURN n", mapOf("id" to n1.id), hydrate = false))
            val node =
                Json
                    .parseToJsonElement(raw)
                    .jsonObject["rows"]!!
                    .jsonArray[0]
                    .jsonArray[0] as JsonObject
            assertEquals(1L, node["kind"]!!.jsonPrimitive.long)
            assertEquals(true, node["stored"]!!.jsonPrimitive.content.toBoolean())
            driver.close()
        }
}
