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
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.KindRegistry
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.engine.schema.RelTypes
import org.neo4j.driver.Driver

/** The steps after `neo4j-admin database import` (spec §7.3 step 3). */
object BulkImport {
    /**
     * Links every address to its owner (the streaming CSVs cannot, without holding every address
     * seen) and installs the constraints and indexes the importer does not create. Batched
     * server-side, so it scales with the graph.
     */
    fun finalize(
        driver: Driver,
        registry: KindRegistry = KindRegistry.quartzKnownKinds(),
        policy: GraphPolicy = GraphPolicy.Default,
    ) {
        SchemaInstaller(driver).install(registry, policy)
        driver.session().use { session ->
            session
                .run(
                    "MATCH (a:${Labels.ADDRESS}) WHERE a.pubkey <> '' AND NOT EXISTS { (a)-[:${RelTypes.OWNED_BY}]->() } " +
                        "CALL (a) { MERGE (u:${Labels.USER} {${Labels.USER_KEY}: a.pubkey}) MERGE (a)-[:${RelTypes.OWNED_BY}]->(u) } " +
                        "IN TRANSACTIONS OF 10000 ROWS",
                ).consume()
        }
    }
}
