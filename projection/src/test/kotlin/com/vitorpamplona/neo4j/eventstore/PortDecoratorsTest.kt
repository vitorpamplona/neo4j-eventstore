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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every [com.vitorpamplona.neo4j.eventstore.engine.GraphIndex] decorator overrides EVERY port
 * member. A decorator that inherits a member does not fail to decorate it — it silently answers
 * with the inherited default. Reads source: an override that calls `super` would pass reflection.
 */
class PortDecoratorsTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "engine/src").isDirectory }

    private val decorators = listOf("engine/src/main/kotlin/com/vitorpamplona/neo4j/eventstore/engine/metrics/MeteredGraphIndex.kt")

    @Test
    fun everyDecoratorOverridesEveryPortMember() {
        val port = File(root(), "engine/src/main/kotlin/com/vitorpamplona/neo4j/eventstore/engine/GraphIndex.kt").readText()
        val members = Regex("(?m)^\\s{4}(?:suspend )?fun (\\w+)").findAll(port).map { it.groupValues[1] }.toSet()
        assertTrue(members.size >= 6, "port member scan found $members")
        for (path in decorators) {
            val source = File(root(), path).readText()
            val overridden = Regex("override (?:suspend )?fun (\\w+)").findAll(source).map { it.groupValues[1] }.toSet()
            val missing = members - overridden
            if (missing.isNotEmpty()) fail("$path does not override $missing")
        }
    }
}
