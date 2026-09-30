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

import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.engine.schema.Derivation
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("integration")
class SchemaInstallerIT {
    @Test
    fun installRecordsTheStampAndRefusesAGraphOfAnotherMajor() {
        val driver = Neo4jTestServer.freshDriver()
        val installer = SchemaInstaller(driver)
        val policy = GraphPolicy(maxTagValueBytes = 128)
        installer.install(policy)
        val meta = installer.meta()!!
        assertEquals(SchemaInstaller.SCHEMA_VERSION, meta["schema_version"])
        assertEquals(policy.hash(), meta["policy_hash"])
        assertEquals(Derivation.stamp(policy), meta[Derivation.PROPERTY])
        assertEquals(Derivation.VERSION.toLong(), meta["derivation_version"])

        // Another minor, or another policy, is recorded over: the per-node stamps drive re-derive.
        driver.session().use { it.run("MATCH (m:Meta) SET m.schema_version = '2.9'").consume() }
        installer.install(GraphPolicy.Default)
        assertEquals(Derivation.stamp(GraphPolicy.Default), installer.meta()!![Derivation.PROPERTY])

        driver.session().use { it.run("MATCH (m:Meta) SET m.schema_version = '1.4'").consume() }
        val refused = assertFailsWith<IllegalStateException> { installer.install(GraphPolicy.Default) }
        assertTrue("1.4" in refused.message!!, refused.message)
        assertEquals("1.4", installer.meta()!!["schema_version"], "a refused graph is left as it was found")
        driver.session().use { it.run("MATCH (m:Meta) DETACH DELETE m").consume() }
        driver.close()
    }

    @Test
    fun theReportIndexesCoverTheRawCategoryOnEveryReportRelation() {
        val driver = Neo4jTestServer.freshDriver()
        SchemaInstaller(driver).install(GraphPolicy.Default)
        val indexed =
            driver.session().use { session ->
                session
                    .run("SHOW INDEXES YIELD labelsOrTypes, properties WHERE labelsOrTypes IS NOT NULL RETURN labelsOrTypes, properties")
                    .list {
                        it["labelsOrTypes"]
                            .asList { v ->
                                v.asString()
                            }.firstOrNull() to it["properties"].asList { v -> v.asString() }
                    }
            }
        for (type in listOf("REPORTED_USER", "REPORTED", "REPORTED_AUTHOR")) {
            assertTrue(type to listOf("report_raw") in indexed, "$type(report_raw) in $indexed")
            assertTrue(type to listOf("report") in indexed, "$type(report) in $indexed")
        }
        assertTrue("Address" to listOf("kind") in indexed)
        // NIP-05 names live on the kind 0, not on the `:User`.
        assertTrue("Data" to listOf("nip05") in indexed, "$indexed")
        assertTrue("User" to listOf("nip05") !in indexed, "$indexed")
        driver.close()
    }
}
