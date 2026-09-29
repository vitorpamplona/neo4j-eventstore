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
package com.vitorpamplona.neo4j.eventstore.engine.client

import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig

/**
 * Creates the constraints and indexes the projection relies on (spec §4.1) and records what the
 * graph was built with in the `:Meta` singleton. Idempotent — safe on every boot — and run after
 * a bulk import, which loads data without them.
 *
 * The uniqueness constraints are not just integrity: every `MERGE` on a key is an index seek
 * through them, and they serialize concurrent writers creating the same node.
 */
class SchemaInstaller(
    private val driver: Driver,
    private val database: String = DEFAULT_DATABASE,
) {
    fun install(
        policy: GraphPolicy,
        awaitSeconds: Long = 600,
    ) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            STATEMENTS.forEach { session.run(it).consume() }
            session.run("CALL db.awaitIndexes(\$seconds)", mapOf("seconds" to awaitSeconds)).consume()
            session
                .run(
                    "MERGE (m:${Labels.META} {singleton: true}) " +
                        "SET m.schema_version = \$schema, m.policy_hash = \$policy REMOVE m.kind_registry_version",
                    mapOf("schema" to SCHEMA_VERSION, "policy" to policy.hash()),
                ).consume()
        }
    }

    /** What `:Meta` records, or null on a graph never installed. */
    fun meta(): Map<String, Any?>? =
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session
                .run("MATCH (m:${Labels.META} {singleton: true}) RETURN properties(m) AS p")
                .list()
                .firstOrNull()
                ?.get("p")
                ?.asMap()
        }

    companion object {
        const val DEFAULT_DATABASE = "neo4j"

        /**
         * The public schema version (spec §8.6): minor for additive changes, major for renames and
         * removals. 2.0: relationship types are the vocabulary's relations (`REPLY`-style names,
         * `docs/vocabulary.md`), no longer `<tag>_<kind>`.
         */
        const val SCHEMA_VERSION = "2.0"

        val STATEMENTS =
            listOf(
                "CREATE CONSTRAINT event_id IF NOT EXISTS FOR (n:${Labels.EVENT}) REQUIRE n.${Labels.EVENT_KEY} IS UNIQUE",
                "CREATE CONSTRAINT user_pubkey IF NOT EXISTS FOR (n:${Labels.USER}) REQUIRE n.${Labels.USER_KEY} IS UNIQUE",
                "CREATE CONSTRAINT address_id IF NOT EXISTS FOR (n:${Labels.ADDRESS}) REQUIRE n.${Labels.ADDRESS_KEY} IS UNIQUE",
                "CREATE CONSTRAINT tag_key IF NOT EXISTS FOR (n:${Labels.TAG}) REQUIRE n.${Labels.TAG_KEY} IS UNIQUE",
                "CREATE CONSTRAINT removed_id IF NOT EXISTS FOR (n:${Labels.REMOVED}) REQUIRE n.id IS UNIQUE",
                // The reconciler's (created_at, id) windows.
                "CREATE INDEX stored_created_at IF NOT EXISTS FOR (n:${Labels.STORED}) ON (n.created_at)",
                "CREATE INDEX stored_kind IF NOT EXISTS FOR (n:${Labels.STORED}) ON (n.kind)",
                "CREATE INDEX stored_expires_at IF NOT EXISTS FOR (n:${Labels.STORED}) ON (n.expires_at)",
                "CREATE INDEX user_nip05 IF NOT EXISTS FOR (n:${Labels.USER}) ON (n.nip05)",
                "CREATE INDEX removed_at IF NOT EXISTS FOR (n:${Labels.REMOVED}) ON (n.at)",
                // Report queries filter the report edges themselves ("impersonation reports",
                // "everything but the invented types"): relationship indexes let a query that is
                // not anchored on one user seek instead of scanning every report.
                "CREATE INDEX reported_user_type IF NOT EXISTS FOR ()-[r:REPORTED_USER]-() ON (r.report)",
                "CREATE INDEX reported_user_raw IF NOT EXISTS FOR ()-[r:REPORTED_USER]-() ON (r.report_raw)",
                "CREATE INDEX reported_author_type IF NOT EXISTS FOR ()-[r:REPORTED_AUTHOR]-() ON (r.report)",
                "CREATE INDEX reported_type IF NOT EXISTS FOR ()-[r:REPORTED]-() ON (r.report)",
            )
    }
}
