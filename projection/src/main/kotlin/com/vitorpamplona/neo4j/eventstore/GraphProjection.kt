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
package com.vitorpamplona.neo4j.eventstore

import com.vitorpamplona.neo4j.eventstore.cypher.CypherAudit
import com.vitorpamplona.neo4j.eventstore.cypher.CypherService
import com.vitorpamplona.neo4j.eventstore.cypher.Hydrator
import com.vitorpamplona.neo4j.eventstore.cypher.ServerSafety
import com.vitorpamplona.neo4j.eventstore.engine.GraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.Neo4jGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.client.SchemaInstaller
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.derive.RoleTable
import com.vitorpamplona.neo4j.eventstore.engine.metrics.MeteredGraphIndex
import com.vitorpamplona.neo4j.eventstore.engine.schema.GraphPolicy
import com.vitorpamplona.neo4j.eventstore.engine.schema.KindRegistry
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.engine.schema.RelTypes
import com.vitorpamplona.neo4j.eventstore.feed.ChangeListener
import com.vitorpamplona.neo4j.eventstore.feed.FeedStats
import com.vitorpamplona.neo4j.eventstore.feed.GraphFeed
import com.vitorpamplona.neo4j.eventstore.reconcile.CursorStore
import com.vitorpamplona.neo4j.eventstore.reconcile.DirtyTracker
import com.vitorpamplona.neo4j.eventstore.reconcile.MirrorReconciler
import com.vitorpamplona.neo4j.eventstore.reconcile.ReconcileLoop
import com.vitorpamplona.neo4j.eventstore.reconcile.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig

/**
 * The front door (spec §2): a Neo4j graph kept an exact projection of [SourceOfTruth], plus the
 * guarded Cypher service over it. The one type a consumer (vespa-relay) names.
 *
 * Wiring, in the relay: pass [listener] to the source's write path (vespa-eventstore's
 * `open(observers = …)` — the shapes match), start [reconcileLoop], and serve [cypher] and
 * [schema]. Nothing here ever writes to the source.
 */
class GraphProjection private constructor(
    private val driver: Driver,
    private val ownsDriver: Boolean,
    private val database: String,
    val index: GraphIndex,
    private val feed: GraphFeed,
    val dirty: DirtyTracker,
    val reconcileLoop: ReconcileLoop,
    val cypher: CypherService,
    private val registry: KindRegistry,
    private val policy: GraphPolicy,
    private val scope: CoroutineScope,
) : AutoCloseable {
    /** Where the source's write path reports acked puts and removes. Never blocks. */
    val listener: ChangeListener get() = feed

    fun feedStats(): FeedStats = feed.stats()

    /**
     * The live schema view behind `GET /graph/schema` (spec §8.6): version, labels and
     * relationship types with counts (each an O(1) count-store read), the role table, the kind
     * registry version and the policy.
     */
    suspend fun schema(): JsonObject =
        withContext(Dispatchers.IO) {
            driver.session(SessionConfig.forDatabase(database)).use { session ->
                val meta = SchemaInstaller(driver, database).meta() ?: emptyMap()
                val labels =
                    listOf(Labels.EVENT, Labels.STORED, Labels.USER, Labels.ADDRESS, Labels.TAG).associateWith { label ->
                        session.run("MATCH (n:$label) RETURN count(n) AS c").single()["c"].asLong()
                    }
                val types =
                    session
                        .run("CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType")
                        .list { it["relationshipType"].asString() }
                        .filter { RelTypes.isSafe(it) }
                        .sorted()
                        .associateWith { type -> session.run("MATCH ()-[r:$type]->() RETURN count(r) AS c").single()["c"].asLong() }
                JsonObject(
                    mapOf(
                        "schema_version" to JsonPrimitive(meta["schema_version"]?.toString() ?: SchemaInstaller.SCHEMA_VERSION),
                        "kind_registry_version" to JsonPrimitive(registry.version),
                        "policy" to
                            JsonObject(
                                mapOf(
                                    "hash" to JsonPrimitive(policy.hash()),
                                    "tag_nodes" to JsonArray(policy.tagNodeNames.sorted().map { JsonPrimitive(it) }),
                                    "excluded_kinds" to JsonArray(policy.excludedKinds.sorted().map { JsonPrimitive(it) }),
                                ),
                            ),
                        "labels" to JsonObject(labels.mapValues { JsonPrimitive(it.value) }),
                        "relationship_types" to JsonObject(types.mapValues { JsonPrimitive(it.value) }),
                        "implied_roles" to
                            JsonArray(
                                RoleTable.implied.map { r ->
                                    JsonObject(
                                        mapOf(
                                            "kinds" to
                                                (
                                                    r.kinds?.sorted()?.let { k -> JsonArray(k.map { JsonPrimitive(it) }) }
                                                        ?: JsonPrimitive("any")
                                                ),
                                            "tag" to JsonPrimitive(r.tag),
                                            "role" to JsonPrimitive(r.role),
                                        ),
                                    )
                                },
                            ),
                    ),
                )
            }
        }

    override fun close() {
        feed.close()
        scope.cancel()
        index.close()
        if (ownsDriver) driver.close()
    }

    companion object {
        /**
         * Connects, installs the schema (idempotent), checks the server is safe to expose to
         * callers' Cypher, and starts the feed consumer. Does NOT start [reconcileLoop] — the
         * embedding process decides which of its processes reconciles (vespa-relay: only the
         * relay, not the sync process).
         */
        fun open(
            url: String,
            user: String,
            password: String,
            source: SourceOfTruth,
            database: String = SchemaInstaller.DEFAULT_DATABASE,
            policy: GraphPolicy = GraphPolicy.Default,
            registry: KindRegistry = KindRegistry.quartzKnownKinds(),
            cursor: CursorStore? = null,
            audit: CypherAudit? = null,
            installSchema: Boolean = true,
            requireSafeServer: Boolean = true,
            queueCapacity: Int = GraphFeed.DEFAULT_CAPACITY,
        ): GraphProjection =
            open(
                driver = GraphDatabase.driver(url, AuthTokens.basic(user, password)),
                ownsDriver = true,
                source = source,
                database = database,
                policy = policy,
                registry = registry,
                cursor = cursor,
                audit = audit,
                installSchema = installSchema,
                requireSafeServer = requireSafeServer,
                queueCapacity = queueCapacity,
            )

        fun open(
            driver: Driver,
            ownsDriver: Boolean,
            source: SourceOfTruth,
            database: String = SchemaInstaller.DEFAULT_DATABASE,
            policy: GraphPolicy = GraphPolicy.Default,
            registry: KindRegistry = KindRegistry.quartzKnownKinds(),
            cursor: CursorStore? = null,
            audit: CypherAudit? = null,
            installSchema: Boolean = true,
            requireSafeServer: Boolean = true,
            queueCapacity: Int = GraphFeed.DEFAULT_CAPACITY,
        ): GraphProjection {
            driver.verifyConnectivity()
            if (installSchema) SchemaInstaller(driver, database).install(registry, policy)
            if (requireSafeServer) {
                val problems = ServerSafety.problems(driver, database)
                check(problems.isEmpty()) { "refusing to serve Cypher from an unsafe Neo4j server: $problems" }
            }
            val index = MeteredGraphIndex(Neo4jGraphIndex(driver, database, EdgeDeriver(registry, policy)))
            val dirty = DirtyTracker()
            val feed = GraphFeed(index, dirty, policy, queueCapacity)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            feed.start(scope)
            val reconciler = MirrorReconciler(source, index, policy)
            return GraphProjection(
                driver = driver,
                ownsDriver = ownsDriver,
                database = database,
                index = index,
                feed = feed,
                dirty = dirty,
                reconcileLoop = ReconcileLoop(reconciler, dirty, cursor),
                cypher = CypherService(driver, database, Hydrator { ids -> source.fetch(ids) }, audit),
                registry = registry,
                policy = policy,
                scope = scope,
            )
        }
    }
}
