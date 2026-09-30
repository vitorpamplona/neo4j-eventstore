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

import com.vitorpamplona.neo4j.eventstore.GraphProjection
import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
class GraphProjectionIT {
    @Test
    fun theSchemaViewIsOneCountStoreStatementAndCloseSavesWhatIsOwed(): Unit =
        runBlocking {
            val driver = Neo4jTestServer.freshDriver()
            val source = SimulatedSource()
            val corpus = GraphCorpus(51)
            repeat(80) { source.put(corpus.next()) }
            val dirtyFile = File.createTempFile("graph-dirty", ".txt").apply { delete() }
            val projection = GraphProjection.open(driver, ownsDriver = false, source = source, dirtyFile = dirtyFile)
            projection.index.apply(source.held.values.toList())

            // Every count is answered from the count store: the one label scan is :Meta's (one node).
            val operators =
                driver.session().use { session ->
                    val plan = session.run("EXPLAIN " + GraphProjection.SCHEMA_COUNTS).consume().plan()
                    generateSequence(listOf(plan)) { level -> level.flatMap { it.children() }.takeIf { it.isNotEmpty() } }
                        .flatten()
                        .map { it.operatorType().substringBefore('@') }
                        .toList()
                }
            assertEquals(1, operators.count { "Scan" in it }, "plan operators: $operators")
            assertTrue(operators.none { "Expand" in it || "Aggregation" in it }, "plan operators: $operators")
            assertTrue("NodeCountFromCountStore" in operators && "RelationshipCountFromCountStore" in operators, "$operators")

            val schema = projection.schema()
            val labels = schema["labels"] as JsonObject
            val types = schema["relationship_types"] as JsonObject
            val counts =
                driver.session().use { s ->
                    s.run("MATCH (n:Data) RETURN count(n) AS stored").single()["stored"].asLong() to
                        s.run("MATCH ()-[r:AUTHOR]->() RETURN count(r) AS c").single()["c"].asLong()
                }
            assertEquals(counts.first, labels["Data"]!!.jsonPrimitive.long)
            assertEquals(counts.second, types["AUTHOR"]!!.jsonPrimitive.long)
            assertTrue(types.values.all { it.jsonPrimitive.long > 0 }, "a type with no edges is absent")
            assertEquals(SchemaInstaller.SCHEMA_VERSION, schema["schema_version"]!!.jsonPrimitive.content)
            assertTrue("max_curated_bytes" in (schema["policy"] as JsonObject))

            // close() stops a running reconcile loop before it saves the dirty file.
            projection.dirty.markCreatedAt(1_700_000_000L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val loop = projection.reconcileLoop.start(scope, everySeconds = 3_600, dirtyOnly = true)
            projection.close()
            assertTrue(loop.isCancelled, "the loop was stopped")
            assertTrue(dirtyFile.exists())
            scope.cancel()
            dirtyFile.delete()
            driver.close()
        }
}
