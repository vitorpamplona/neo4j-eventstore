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

import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig

/**
 * The server-side half of the guard (spec §8.2 layer 3), asserted at boot: the service refuses to
 * serve Cypher from a server where a plugin function could reach past [CypherGuard] or
 * `LOAD CSV` could read the server's files.
 */
object ServerSafety {
    /** Every problem found; empty when the server is safe to expose. */
    fun problems(
        driver: Driver,
        database: String = "neo4j",
    ): List<String> {
        val out = ArrayList<String>()
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            // Plugin FUNCTIONS are the gap: CypherGuard allowlists procedure calls from the plan,
            // but a function runs inside any expression. Built-ins are pure; a user-defined one
            // (APOC, GDS, custom) is not checked, so none may be installed. (Procedures, including
            // the fleetManagement.* ones the image ships, are unreachable past the allowlist.)
            val plugins =
                session
                    .run("SHOW FUNCTIONS YIELD name, isBuiltIn WHERE NOT isBuiltIn RETURN name")
                    .list { it["name"].asString() }
            if (plugins.isNotEmpty()) out += "user-defined (plugin) functions are installed: ${plugins.take(5)}"
            val csv =
                session
                    .run("SHOW SETTINGS YIELD name, value WHERE name = 'dbms.security.allow_csv_import_from_file_urls' RETURN value")
                    .list { it["value"].asString() }
                    .firstOrNull()
            if (csv != null &&
                !csv.equals("false", ignoreCase = true)
            ) {
                out += "dbms.security.allow_csv_import_from_file_urls must be false (is $csv)"
            }
        }
        return out
    }
}
