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

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The Neo4j SERVER is GPLv3; linking it would put this library under GPL terms (spec §10). Only
 * the Apache-2.0 driver (and its Bolt connection modules) may be on the classpath.
 */
class NoEmbeddedNeo4jTest {
    @Test
    fun onlyTheDriverIsLinked() {
        val allowed = listOf("neo4j-java-driver", "neo4j-bolt-connection")
        val jars =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it).name }
                .filter { it.startsWith("neo4j") && it.endsWith(".jar") }
        val server = jars.filter { jar -> allowed.none { jar.startsWith(it) } }
        if (server.isNotEmpty()) fail("Neo4j server artifacts on the classpath: $server")
    }
}
