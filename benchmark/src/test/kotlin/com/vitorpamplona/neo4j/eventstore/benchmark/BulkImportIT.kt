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

import com.vitorpamplona.neo4j.eventstore.engine.client.Neo4jGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.memory.InMemoryGraphIndex
import com.vitorpamplona.neo4j.eventstore.sim.GraphCorpus
import com.vitorpamplona.neo4j.eventstore.sim.Histories
import com.vitorpamplona.neo4j.eventstore.sim.SimulatedSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bulk path (spec §7.3) builds the SAME graph as the online path: a corpus written through
 * [BulkCsvWriter], loaded by the real `neo4j-admin database import full` in a fresh server,
 * finalized, must dump equal to [InMemoryGraphIndex] applying the same events. It also proves
 * the importer keeps the FIRST occurrence under `--skip-duplicate-nodes` (held events beat
 * stubs; named users beat bare ones), which the writer's file order relies on.
 */
@Tag("integration")
class BulkImportIT {
    @Test
    fun bulkImportEqualsOnlineApply(): Unit =
        runBlocking {
            assumeTrue(System.getProperty("itNeo4j") == null, "needs its own container (imports into an empty server)")
            assumeTrue(Neo4jTestServer.dockerAvailable(), "Docker is not available; skipping")

            val random = Random(3)
            val source = SimulatedSource()
            Histories.drive(source, GraphCorpus(3), random, steps = 400, resurrections = false)
            val events = source.held.values.toList()

            // World-readable: the server image imports as its own `neo4j` user.
            val dir =
                Files.createTempDirectory("graph-csv").toFile().apply {
                    setReadable(true, false)
                    setExecutable(true, false)
                }
            val writer = BulkCsvWriter(dir)
            writer.use { w -> events.forEach { w.write(it) } }
            dir.listFiles()!!.forEach { it.setReadable(true, false) }
            val args = writer.importArguments("/import-csv").joinToString(" ")

            val image = System.getProperty("neo4jImage") ?: "neo4j:2026.09-community"
            GenericContainer(DockerImageName.parse(image))
                .withEnv("NEO4J_AUTH", "neo4j/${Neo4jTestServer.PASSWORD}")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.path, 0x1ff), "/import-csv")
                .withCreateContainerCmdModifier { cmd ->
                    cmd.withEntrypoint(
                        "sh",
                        "-c",
                        "/startup/docker-entrypoint.sh neo4j-admin $args && exec /startup/docker-entrypoint.sh neo4j",
                    )
                }.withExposedPorts(7687)
                .waitingFor(Wait.forLogMessage(".*Started.*", 1).withStartupTimeout(Duration.ofMinutes(2)))
                .use { container ->
                    try {
                        container.start()
                    } catch (e: Exception) {
                        println("CONTAINER LOGS:\n" + container.logs)
                        throw e
                    }
                    val driver =
                        GraphDatabase.driver(
                            "bolt://${container.host}:${container.getMappedPort(7687)}",
                            AuthTokens.basic("neo4j", Neo4jTestServer.PASSWORD),
                        )
                    BulkImport.finalize(driver)

                    val expected = InMemoryGraphIndex().apply { apply(events, authoritative = true) }.dump()
                    val actual = Neo4jGraphIndex(driver).dump()
                    assertEquals(expected.nodes.size, actual.nodes.size, "node count")
                    assertEquals(expected.edges.size, actual.edges.size, "edge count")
                    assertEquals(expected, actual)
                    driver.close()
                }
            dir.deleteRecursively()
            Unit
        }
}
