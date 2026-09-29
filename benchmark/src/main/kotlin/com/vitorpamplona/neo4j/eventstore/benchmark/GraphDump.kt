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

import com.vitorpamplona.quartz.nip01Core.core.Event
import java.io.File

/**
 * `graphDump <events.jsonl|-> <out-dir>`: one NIP-01 event JSON per line (a Vespa dump, a relay
 * export) → neo4j-admin CSVs, and the import command to run. See spec §7.3 for the whole
 * procedure (start the feed first, import, finalize, reconcile from T0).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: graphDump <events.jsonl|-> <out-dir>" }
    val input = if (args[0] == "-") System.`in`.bufferedReader() else File(args[0]).bufferedReader()
    val out = File(args[1])
    var bad = 0L
    BulkCsvWriter(out).use { writer ->
        input.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val event = runCatching { Event.fromJson(line) }.getOrNull()
                if (event == null) bad++ else writer.write(event)
            }
        }
        System.err.println("events: ${writer.events}, unparseable lines: $bad")
        println("neo4j-admin " + writer.importArguments(out.absolutePath).joinToString(" "))
        println("then: BulkImport.finalize(driver), and reconcile from the dump's start time")
    }
}
