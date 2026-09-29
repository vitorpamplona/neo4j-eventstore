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
 * THE LAYERING, ASSERTED (spec §2). Packages form a strict order — `:engine` never learns about
 * `:projection`, a lower layer never imports a higher one, nothing below the facade imports it —
 * and a package missing from the tables fails the build: a new package is a layering decision,
 * made here. Reads SOURCE, like vespa-eventstore's: package structure is what is asserted.
 */
class ModuleBoundariesTest {
    private val base = "com.vitorpamplona.neo4j.eventstore"

    /** `:engine`: the vocabulary, the pure derivation, the port, its decorators, its implementations. */
    private val engineLayer =
        mapOf(
            "engine.schema" to 0,
            "engine.derive" to 1,
            "engine" to 2,
            "engine.metrics" to 3,
            "engine.memory" to 4,
            "engine.client" to 4,
        )

    /** `:projection`: reconcile and cypher stand alone, the feed uses reconcile's dirty tracker, the facade composes all. */
    private val projectionLayer =
        mapOf(
            "reconcile" to 0,
            "cypher" to 0,
            "feed" to 1,
            "" to 2,
        )

    private data class Source(
        val file: File,
        val pkg: String,
        val imports: List<String>,
    )

    private fun root(): File = listOf(File("."), File("..")).first { File(it, "engine/src").isDirectory }

    private fun sources(module: String): List<Source> =
        File(root(), "$module/src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { f ->
                val lines = f.readLines()
                Source(
                    f,
                    lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() ?: "",
                    lines.filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() },
                )
            }.toList()

    private fun relative(pkg: String) = if (pkg == base) "" else pkg.removePrefix("$base.")

    @Test
    fun theEngineNeverImportsTheProjection() {
        val bad =
            sources("engine").flatMap { s ->
                s.imports.filter { it.startsWith(base) && !it.startsWith("$base.engine") }.map { "${s.file.name}: $it" }
            }
        if (bad.isNotEmpty()) fail("engine imports projection: $bad")
    }

    @Test
    fun enginePackagesImportOnlyTheirLayerOrBelow() {
        val bad = ArrayList<String>()
        for (s in sources("engine").filter { "/main/" in it.file.path }) {
            val mine = engineLayer[relative(s.pkg)] ?: continue
            for (imp in s.imports.filter { it.startsWith("$base.engine") }) {
                val pkg = relative(imp.substringBeforeLast('.'))
                val theirs = engineLayer[pkg] ?: error("${s.file.name}: import of unlisted package $pkg")
                if (theirs > mine) bad += "${s.file.name} (${relative(s.pkg)}) imports $imp"
            }
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    @Test
    fun projectionPackagesImportOnlyTheirLayerOrBelow() {
        val bad = ArrayList<String>()
        for (s in sources("projection").filter { "/main/" in it.file.path }) {
            val mine = projectionLayer[relative(s.pkg)] ?: error("${s.file.name}: package ${s.pkg} is not in the layer table")
            for (imp in s.imports.filter { it.startsWith(base) && !it.startsWith("$base.engine") }) {
                val pkg = relative(imp.substringBeforeLast('.'))
                val theirs = projectionLayer[pkg] ?: error("${s.file.name}: import of unlisted package $pkg")
                if (theirs > mine || (theirs == mine && pkg != relative(s.pkg))) bad += "${s.file.name} (${relative(s.pkg)}) imports $imp"
            }
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    @Test
    fun everyMainPackageIsInALayerTable() {
        sources("engine").filter { "/main/" in it.file.path }.forEach {
            assertTrue(relative(it.pkg) in engineLayer, "${it.file.name}: ${it.pkg} not in the engine layer table")
        }
    }

    @Test
    fun aTestNamedAfterAClassLivesInThatClassesPackage() {
        val all = sources("engine") + sources("projection")
        val declared = HashMap<String, String>()
        val decl = Regex("^(?:[a-z]+ )*(?:class|object|interface|fun interface) ([A-Z]\\w*)")
        all.filter { "/main/" in it.file.path }.forEach { s ->
            s.file.readLines().forEach { l ->
                decl.find(l)?.let {
                    declared[it.groupValues[1]] =
                        s.pkg
                }
            }
        }
        assertTrue(declared.size > 20, "declaration scan found only ${declared.size}")
        val bad = ArrayList<String>()
        for (s in all.filter { "/test/" in it.file.path }) {
            val subject =
                s.file.nameWithoutExtension
                    .removeSuffix("Test")
                    .removeSuffix("IT")
            if (subject == s.file.nameWithoutExtension) continue
            val home = declared[subject] ?: continue
            if (home != s.pkg) bad += "${s.file.name} is in ${s.pkg}, but $subject is in $home"
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }
}
