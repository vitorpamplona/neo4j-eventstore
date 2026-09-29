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
import com.vitorpamplona.neo4j.eventstore.cypher.ServerSafety
import com.vitorpamplona.neo4j.eventstore.engine.client.Neo4jGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.KindRegistry
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hostile battery of spec §8.5: every query must be REJECTED before it executes, or fail
 * inside Neo4j with no side effect — asserted by comparing a fingerprint of the whole database
 * (counts, `:Meta`, the user list, the schema) before and after the lot.
 */
@Tag("integration")
class CypherGuardIT {
    private val hostile =
        listOf(
            // Writes, in every spelling.
            "CREATE (:Pwned)",
            "MATCH (n:User) SET n.name = 'pwned'",
            "match (n:User) set n.name = 'pwned'",
            "MATCH (n:User) /* innocuous */ SET n.name = 'pwned'",
            "MATCH (n:User) REMOVE n.name",
            "MATCH (n:Event) DETACH DELETE n",
            "MATCH (n) DELETE n",
            "MERGE (:Pwned {x: 1})",
            "FOREACH (x IN [1] | CREATE (:Pwned))",
            "CALL { CREATE (:Pwned) } IN TRANSACTIONS",
            "UNWIND range(1, 3) AS i CREATE (:Pwned {i: i})",
            // Schema and admin.
            "CREATE INDEX pwned FOR (n:User) ON (n.x)",
            "DROP CONSTRAINT event_id",
            "CREATE USER pwned SET PASSWORD 'pwnedpwned'",
            "ALTER USER neo4j SET PASSWORD 'pwnedpwned'",
            "SHOW USERS",
            "SHOW TRANSACTIONS",
            "TERMINATE TRANSACTIONS 'neo4j-transaction-1'",
            "SHOW SETTINGS",
            "SHOW PROCEDURES",
            "STOP DATABASE neo4j",
            "USE system SHOW USERS",
            // Files and the network.
            "LOAD CSV FROM 'file:///etc/passwd' AS line RETURN line",
            "LOAD CSV FROM 'http://169.254.169.254/latest/meta-data/' AS line RETURN line",
            "load csv from 'file:///etc/passwd' as l return l",
            // Procedures.
            "CALL dbms.components() YIELD name RETURN name",
            "CALL dbms.listConfig() YIELD name RETURN name",
            "CALL db.createLabel('Pwned')",
            "CALL db.createProperty('pwned')",
            "CALL apoc.help('x')",
            "CALL db.awaitIndexes(1)",
            // Procedures hidden inside expressions (subquery expressions the planner may keep as
            // nested plans rather than child operators).
            "RETURN COLLECT { CALL dbms.listConfig() YIELD name RETURN name } AS x",
            "RETURN CASE WHEN rand() < 2 THEN COLLECT { CALL dbms.listConfig() YIELD name RETURN name } ELSE [] END AS x",
            "RETURN COUNT { CALL dbms.components() YIELD name RETURN name } AS c",
            "MATCH (n) WHERE EXISTS { CALL dbms.listConfig() YIELD name RETURN name } RETURN n LIMIT 1",
            "RETURN [x IN range(1, 2) | COLLECT { CALL dbms.listConfig() YIELD name RETURN name }] AS x",
            "CALL () { CALL dbms.listConfig() YIELD name RETURN name } RETURN name",
            "MATCH (n) WITH n LIMIT 1 CALL (n) { CALL dbms.listConfig() YIELD name RETURN name } RETURN name",
            // Evasion.
            "MATCH (n) RETURN n; CREATE (:Pwned)",
            "RETURN 1 UNION CREATE (:Pwned) RETURN 2",
            "EXPLAIN CREATE (:Pwned)",
            "PROFILE CREATE (:Pwned)",
        )

    private fun fingerprint(driver: Driver): String =
        driver.session().use { s ->
            val counts = s.run("MATCH (n) WITH count(n) AS n MATCH ()-[r]->() RETURN n, count(r) AS r").single()
            val labels = s.run("CALL db.labels() YIELD label RETURN collect(label) AS l").single()["l"].asList()
            val meta = s.run("MATCH (m:Meta) RETURN properties(m) AS p").list().map { it["p"].asMap() }
            val names = s.run("MATCH (u:User) RETURN collect(u.name) AS n").single()["n"].asList()
            val indexes = s.run("SHOW INDEXES YIELD name RETURN collect(name) AS n").single()["n"].asList()
            val users =
                driver
                    .session(
                        org.neo4j.driver.SessionConfig
                            .forDatabase("system"),
                    ).use { sys ->
                        sys.run("SHOW USERS YIELD user RETURN collect(user) AS u").single()["u"].asList()
                    }
            "$counts|${labels.sortedBy {
                it.toString()
            }}|$meta|${names.sortedBy { it.toString() }}|${indexes.sortedBy { it.toString() }}|$users"
        }

    @Test
    fun hostileQueriesNeverChangeTheDatabase() =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(KindRegistry.quartzKnownKinds(), GraphPolicy.Default)
            val corpus = GraphCorpus(5)
            Neo4jGraphIndex(driver).apply((0 until 60).map { corpus.next() })
            val service = CypherService(driver)
            val before = fingerprint(driver)

            val executed = mutableListOf<String>()
            for (q in hostile) {
                val outcome = runCatching { service.query(CypherRequest(q)).first }.getOrNull()
                if (outcome is CypherService.Outcome.Ok) executed += q
            }
            assertEquals(before, fingerprint(driver), "the database changed")
            if (executed.isNotEmpty()) fail("hostile queries were allowed to run: $executed")
            driver.close()
        }

    @Test
    fun ordinaryReadsAndIntrospectionStillWork() =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            SchemaInstaller(driver).install(KindRegistry.quartzKnownKinds(), GraphPolicy.Default)
            val corpus = GraphCorpus(6)
            Neo4jGraphIndex(driver).apply((0 until 30).map { corpus.next() })
            val service = CypherService(driver)
            for (q in listOf(
                "MATCH (n:Event:Stored) RETURN count(n) AS n",
                "CALL db.labels() YIELD label RETURN label",
                "CALL db.relationshipTypes()",
            )) {
                val (outcome, json) = service.query(CypherRequest(q))
                assertTrue(outcome is CypherService.Outcome.Ok, "$q -> $outcome")
                assertTrue(json.startsWith("{\"columns\":"), json)
            }
            driver.close()
        }

    @Test
    fun theTestServerPassesTheBootSafetyCheck() {
        val driver = Neo4jTestServer.freshDriver()
        assertEquals(emptyList(), ServerSafety.problems(driver))
        driver.close()
    }
}
