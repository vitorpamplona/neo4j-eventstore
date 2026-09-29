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
package com.vitorpamplona.neo4j.eventstore.cypher

import org.neo4j.driver.Session
import org.neo4j.driver.summary.Plan
import org.neo4j.driver.summary.QueryType
import org.neo4j.driver.summary.ResultSummary

/**
 * Decides whether a caller's Cypher may run (spec §8.2). Neo4j Community has no role-based
 * access control — the one account the service uses can write, create users, call any
 * procedure and read files — so the decision is made here, from the query's PLAN, before
 * anything executes.
 *
 * Why the plan and not the text: the planner has already resolved comments, casing, escapes and
 * aliases into operators. And why more than the read-only check: measured on 2026.09, `LOAD CSV
 * FROM 'file:///etc/passwd'` and `SHOW TRANSACTIONS` both plan as READ_ONLY.
 */
class CypherGuard(
    private val database: String,
    private val allowedProcedures: Set<String> = DEFAULT_ALLOWED_PROCEDURES,
) {
    sealed interface Verdict {
        data object Allowed : Verdict

        data class Rejected(
            val reason: String,
        ) : Verdict
    }

    /** Plans [query] with `EXPLAIN` (nothing executes) and judges the plan. */
    fun check(
        session: Session,
        query: String,
        params: Map<String, Any?>,
    ): Verdict {
        val summary =
            try {
                session.run("EXPLAIN $query", params).consume()
            } catch (e: Exception) {
                return Verdict.Rejected("does not plan: " + (e.message ?: e::class.simpleName))
            }
        return judge(summary)
    }

    fun judge(summary: ResultSummary): Verdict {
        if (summary.queryType() != QueryType.READ_ONLY) return Verdict.Rejected("not read-only (${summary.queryType()})")
        val db = summary.database()?.name()
        if (db != null && db != database) return Verdict.Rejected("targets database $db")
        if (!summary.hasPlan()) return Verdict.Rejected("no plan")
        return walk(summary.plan())
    }

    private fun walk(plan: Plan): Verdict {
        val op = plan.operatorType().substringBefore('@')
        when {
            op == "LoadCSV" -> {
                return Verdict.Rejected("LOAD CSV is not allowed")
            }

            op.startsWith("Show") || op.startsWith("Terminate") -> {
                return Verdict.Rejected("$op is not allowed")
            }

            op == "ProcedureCall" -> {
                val details = plan.arguments()["Details"]?.asString() ?: ""
                val name = details.substringBefore('(').trim()
                if (name !in allowedProcedures) return Verdict.Rejected("procedure $name is not allowed")
            }
        }
        for (child in plan.children()) {
            val verdict = walk(child)
            if (verdict != Verdict.Allowed) return verdict
        }
        return Verdict.Allowed
    }

    companion object {
        /** Read-only schema introspection. Everything else — dbms.*, plugins — is out. */
        val DEFAULT_ALLOWED_PROCEDURES =
            setOf(
                "db.labels",
                "db.relationshipTypes",
                "db.propertyKeys",
                "db.schema.visualization",
                "db.schema.nodeTypeProperties",
                "db.schema.relTypeProperties",
            )
    }
}
