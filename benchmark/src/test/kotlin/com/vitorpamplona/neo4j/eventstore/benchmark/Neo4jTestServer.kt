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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

/**
 * The Neo4j server integration tests run against: a testcontainers `neo4j:<pinned>-community`
 * (the CI path), or an already-running server named by `-DitNeo4j=bolt://…` (fast local
 * iteration). Self-skips — never fails — without Docker, like vespa-eventstore's ITs.
 */
object Neo4jTestServer {
    const val PASSWORD = "integration-password"

    private val container: Neo4jContainer<*>? by lazy {
        if (System.getProperty("itNeo4j") != null) return@lazy null
        val image = DockerImageName.parse(System.getProperty("neo4jImage") ?: "neo4j:2026.09-community").asCompatibleSubstituteFor("neo4j")
        Neo4jContainer(image).withAdminPassword(PASSWORD).also { it.start() }
    }

    fun dockerAvailable(): Boolean = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

    /** A driver on a WIPED database (every node gone; constraints and indexes kept). */
    fun freshDriver(): Driver {
        val external = System.getProperty("itNeo4j")
        if (external == null) assumeTrue(dockerAvailable(), "Docker is not available; skipping Neo4j integration test")
        val url = external ?: container!!.boltUrl
        val password = System.getProperty("itNeo4jPassword") ?: PASSWORD
        val driver = GraphDatabase.driver(url, AuthTokens.basic("neo4j", password))
        driver.session().use { it.run("MATCH (n) CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume() }
        return driver
    }
}
