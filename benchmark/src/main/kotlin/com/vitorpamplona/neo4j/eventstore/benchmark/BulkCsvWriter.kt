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

import com.vitorpamplona.neo4j.eventstore.engine.derive.AddressKey
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.derive.Extractors
import com.vitorpamplona.neo4j.eventstore.engine.derive.NodeKind
import com.vitorpamplona.neo4j.eventstore.engine.schema.Labels
import com.vitorpamplona.neo4j.eventstore.engine.schema.RelTypes
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.io.BufferedWriter
import java.io.File

/**
 * The initial load (spec §7.3): events → `neo4j-admin database import full` CSVs, through the
 * SAME [EdgeDeriver] the live projector uses, so a bulk-loaded graph and an online-applied one
 * are identical (BulkImportIT).
 *
 * STREAMING, no in-memory dedup (500M events will not fit): every node is written wherever it
 * is seen and `--skip-duplicate-nodes` keeps the FIRST occurrence, so files are ordered for
 * that — held events before stubs, users with kind-0 names before bare references. What the
 * importer cannot do is left to [BulkImport.finalize] (OWNED_BY) and to the reconciler
 * (a dump spans time, so an old and a new version of one slot may both be present; the
 * reconciler's authoritative pass removes the loser as an "extra").
 */
class BulkCsvWriter(
    private val dir: File,
    private val deriver: EdgeDeriver = EdgeDeriver(),
) : AutoCloseable {
    private val files = LinkedHashMap<String, BufferedWriter>()
    var events = 0L
        private set

    private fun out(
        name: String,
        header: String,
    ): BufferedWriter =
        files.getOrPut(name) {
            dir.mkdirs()
            File(dir, name).bufferedWriter().also {
                it.write(header)
                it.newLine()
            }
        }

    private fun q(value: String?): String = if (value == null) "" else "\"" + value.replace("\"", "\"\"") + "\""

    private fun n(value: Any?): String = value?.toString() ?: ""

    fun write(event: Event) {
        if (!deriver.policy.admits(event.kind)) return
        val doc = deriver.derive(event)
        events++

        out(
            STORED,
            "id:ID(Event),kind:long,created_at:long,d,expires_at:long,content,msats:long,title,name,display_name,nip05,:LABEL",
        ).apply {
            val p = doc.nodeProps
            write(
                listOf(
                    q(doc.id),
                    n(doc.kind),
                    n(doc.createdAt),
                    q(p["d"] as String?),
                    n(p["expires_at"]),
                    q(p[Extractors.CONTENT] as String?),
                    n(p[Extractors.MSATS]),
                    q(p[Extractors.TITLE] as String?),
                    q(p["name"] as String?),
                    q(p["display_name"] as String?),
                    q(p["nip05"] as String?),
                    "${Labels.EVENT};${Labels.STORED}",
                ).joinToString(","),
            )
            newLine()
        }
        doc.authorProps?.let { props ->
            out(USERS_NAMED, "pubkey:ID(User),name,display_name,nip05").apply {
                write(listOf(q(doc.pubkey), q(props["name"]), q(props["display_name"]), q(props["nip05"])).joinToString(","))
                newLine()
            }
        }

        for (edge in doc.edges) {
            val key = edge.target.key
            when (edge.target.kind) {
                NodeKind.EVENT -> {
                    out(STUBS, "id:ID(Event),:LABEL").apply {
                        write("${q(key)},${Labels.EVENT}")
                        newLine()
                    }
                }

                NodeKind.USER -> {
                    out(USERS, "pubkey:ID(User)").apply {
                        write(q(key))
                        newLine()
                    }
                }

                NodeKind.TAG -> {
                    out(TAGS, "key:ID(Tag),name,value").apply {
                        write(listOf(q(key), q(key.substringBefore(':')), q(key.substringAfter(':'))).joinToString(","))
                        newLine()
                    }
                }

                NodeKind.ADDRESS -> {
                    val a = AddressKey.parse(key)
                    out(ADDRESSES, "id:ID(Address),kind:long,pubkey,d").apply {
                        write(listOf(q(key), n(a?.kind), q(a?.pubkey), q(a?.d)).joinToString(","))
                        newLine()
                    }
                }
            }
            val file =
                when (edge.target.kind) {
                    NodeKind.EVENT -> RELS_EVENT
                    NodeKind.USER -> RELS_USER
                    NodeKind.ADDRESS -> RELS_ADDRESS
                    NodeKind.TAG -> RELS_TAG
                }
            val space = edge.target.kind.label
            require(RelTypes.isSafe(edge.type))
            out(file, ":START_ID(Event),:END_ID($space),:TYPE,roles:string[],via,kind:long,report,rank:long,followers:long").apply {
                val p = edge.props

                @Suppress("UNCHECKED_CAST")
                val roles = (p["roles"] as List<String>?)?.joinToString(";")
                write(
                    listOf(
                        q(doc.id),
                        q(key),
                        edge.type,
                        q(roles),
                        q(p["via"] as String?),
                        n(p["kind"]),
                        q(p[Extractors.REPORT] as String?),
                        n(p[Extractors.RANK]),
                        n(p[Extractors.FOLLOWERS]),
                    ).joinToString(","),
                )
                newLine()
            }
        }
    }

    /** The `neo4j-admin` arguments for the files written, in the order `--skip-duplicate-nodes` needs. */
    fun importArguments(pathPrefix: String): List<String> {
        val nodes = listOf(STORED, STUBS, USERS_NAMED, USERS, ADDRESSES, TAGS).filter { it in files }
        val rels = listOf(RELS_EVENT, RELS_USER, RELS_ADDRESS, RELS_TAG).filter { it in files }
        // The database name goes FIRST: `--relationships` takes a variable number of files and
        // would swallow a trailing positional argument as one more.
        return listOf(
            "database",
            "import",
            "full",
            "neo4j",
            "--overwrite-destination",
            "--skip-duplicate-nodes=true",
            "--multiline-fields=true",
        ) +
            nodes.map { "--nodes=" + (LABEL_OF[it]?.let { label -> "$label=" } ?: "") + "$pathPrefix/$it" } +
            rels.map { "--relationships=$pathPrefix/$it" }
    }

    override fun close() = files.values.forEach { it.close() }

    companion object {
        const val STORED = "events_stored.csv"
        const val STUBS = "events_stub.csv"
        const val USERS_NAMED = "users_named.csv"
        const val USERS = "users.csv"
        const val ADDRESSES = "addresses.csv"
        const val TAGS = "tags.csv"
        const val RELS_EVENT = "rels_event.csv"
        const val RELS_USER = "rels_user.csv"
        const val RELS_ADDRESS = "rels_address.csv"
        const val RELS_TAG = "rels_tag.csv"

        // Files without a `:LABEL` column get their label on the command line (events carry
        // theirs per row: held ones are Event;Stored, stubs just Event).
        private val LABEL_OF = mapOf(USERS_NAMED to Labels.USER, USERS to Labels.USER, ADDRESSES to Labels.ADDRESS, TAGS to Labels.TAG)
    }
}
