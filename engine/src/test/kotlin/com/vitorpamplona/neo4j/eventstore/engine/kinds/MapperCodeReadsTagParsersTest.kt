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
package com.vitorpamplona.neo4j.eventstore.engine.kinds

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Quartz's Tag classes own the parsing of their tags. Mapper code — every `<Package>Links.kt` in
 * `kinds/` — takes its values from Quartz's Tag parsers, `TAG_NAME` constants and the accessors
 * built on them, never from raw tag slots or string-literal tag names, and its props are the
 * relation's typed class. Where the pinned Quartz has no parser for a tag, the package's
 * `<Package>Tags.kt` holds a small one (the only place in `kinds/` that reads slots), shaped like
 * Quartz's own (`TAG_NAME`, `parse`), so it can move upstream unchanged. This reads the sources,
 * so a regression fails here instead of in review.
 */
class MapperCodeReadsTagParsersTest {
    private val checks =
        listOf(
            Regex("""\b\w+\[\d+]""") to "tag slot indexing: read through a Tag parser",
            // The same slot read spelled as a call, on a raw tag: one named like one, a tag a
            // finder lambda picked (`tags.firstOrNull { … }?.get(1)`), or the first/last tag.
            // A positional pick among PARSED tags (`eTags.getOrNull(1)`: NIP-15's bid, then its
            // auction) is the spec's ordering, not a slot, so a plain list name is not matched.
            Regex(
                """(?:\b(?:it|t|entry|\w*[Tt]ag)|}|\b(?:first|last|firstOrNull|lastOrNull|single|singleOrNull)\(\))\??""" +
                    """\.(?:get|getOrNull|getOrElse|elementAt|elementAtOrNull)\(\s*\d+""",
            ) to "tag slot read by index: read through a Tag parser",
            Regex("""\b(?:it|tag|entry|t)\.size\b""") to "tag size check: the Tag parser decides what is well-formed",
            Regex("""\b(?:mapOf|buildMap|hashMapOf|HashMap)\b""") to "raw-map props: use the relation's props class",
            Regex(""""[A-Za-z][A-Za-z0-9_-]{0,2}"""") to "short string literal: use the Tag parser's TAG_NAME",
        )

    /** Not mapper code: the registry, and the one place that reads an event's own slot. */
    private val exempt = setOf("KindLinks.kt")

    @Test
    fun mapperCodeReadsTagsOnlyThroughTagParsers() {
        val root = listOf(File("src/main/kotlin"), File("engine/src/main/kotlin")).first { it.isDirectory }
        val kinds = File(root, "com/vitorpamplona/neo4j/eventstore/engine/kinds")
        assertTrue(kinds.isDirectory, "kinds/ not found from ${File(".").absolutePath}")

        val findings = mutableListOf<String>()
        kinds
            .listFiles { f -> f.isFile && f.name.endsWith("Links.kt") && f.name !in exempt }!!
            .sortedBy { it.name }
            .forEach { file ->
                stripComments(file.readText()).lines().forEachIndexed { index, row ->
                    if (row.startsWith("import ") || row.startsWith("package ")) return@forEachIndexed
                    checks.forEach { (regex, why) ->
                        if (regex.containsMatchIn(row)) findings += "${file.name}:${index + 1}: $why\n    ${row.trim()}"
                    }
                }
            }
        assertTrue(findings.isEmpty(), "${findings.size} raw tag access(es) in mapper code:\n" + findings.joinToString("\n"))
    }

    /** Comments out, newlines kept so reported line numbers stay right. */
    private fun stripComments(text: String) =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { it.value.filter { c -> c == '\n' } }
            .replace(Regex("""//[^\n]*"""), "")
}
