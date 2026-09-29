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
package com.vitorpamplona.neo4j.eventstore.engine.derive

import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag

/**
 * Semantic roles of references (spec §5.3).
 *
 * A role is STORED (a `roles` list on the relationship) only where the relationship type leaves
 * it open — a kind-1 `e` can be root, reply, mention or fork; a kind-7 `e` can be the reacted-to
 * note or context. Everywhere else the type IMPLIES the role (`p_3` is always a follow) and it is
 * documented in [implied] rather than written onto billions of edges.
 */
object RoleTable {
    /** NIP-10 threading kinds: `e` markers (or the positional rule) decide root / reply / mention / fork. */
    val THREADED_KINDS = setOf(1, 42, 1311, 2004, 30818, 1617, 1630, 1631, 1632, 1633)

    /** NIP-18 reposts: the LAST `e` / `a` is the reposted target, earlier ones are context. */
    val REPOST_KINDS = setOf(6, 16)

    /** NIP-25 reactions: the LAST `e` / `a` is the reacted-to target, earlier ones are context. */
    val REACTION_KINDS = setOf(7)

    const val ROOT = "root"
    const val REPLY = "reply"
    const val MENTION = "mention"
    const val FORK = "fork"
    const val REPOST = "repost"
    const val REACTION = "reaction"
    const val CONTEXT = "context"
    const val ZAPPER = "zapper"

    /**
     * The per-event facts the per-tag decision needs: whether any `e` is marked (NIP-10 marked vs
     * legacy positional), and where the `e` tags and the last `e` / `a` sit.
     */
    class Context(
        kind: Int,
        tags: Array<Array<String>>,
    ) {
        val kind = kind
        val eIndexes: List<Int> = tags.indices.filter { tags[it].size >= 2 && tags[it][0] == "e" }
        val lastA: Int = tags.indices.lastOrNull { tags[it].size >= 2 && tags[it][0] == "a" } ?: -1

        // NIP-10 exactly as Quartz reads it. ROOT = BaseThreadedEvent.root(): the marked root,
        // else the first unmarked `e`. REPLY = the DIRECT PARENT, BaseThreadedEvent.replyingTo():
        // the last marked reply, else the marked root (NIP-10: a direct reply to the root carries
        // only a `root` marker), else the last unmarked `e` — so following `reply` edges walks a
        // whole reply tree, direct replies to the root included.
        private val markedRoot = tags.indexOfFirst { MarkedETag.parseRoot(it) != null }
        val rootIndex: Int = markedRoot.takeIf { it >= 0 } ?: tags.indexOfFirst { MarkedETag.parseUnmarkedRoot(it) != null }
        val replyIndex: Int =
            tags.indexOfLast { MarkedETag.parseReply(it) != null }.takeIf { it >= 0 }
                ?: markedRoot.takeIf { it >= 0 }
                ?: tags.indexOfLast { MarkedETag.parseUnmarkedReply(it) != null }
    }

    /** The roles to STORE for tag [index] (null when the type implies them, or there are none). */
    fun storedRoles(
        ctx: Context,
        index: Int,
        tag: Array<String>,
    ): List<String>? {
        val name = tag[0]
        return when (ctx.kind) {
            in THREADED_KINDS -> if (name == "e") threaded(ctx, index, tag) else null
            in REPOST_KINDS -> targetOrContext(ctx, index, name, REPOST)
            in REACTION_KINDS -> targetOrContext(ctx, index, name, REACTION)
            else -> null
        }
    }

    // NIP-10, delegated to Quartz for WHICH tag is the root and the reply; a tag's own marker
    // (mention, fork, or a second reply) is kept as written; an `e` that is none of these is a
    // mention. One unmarked `e` is therefore both root and reply, as NIP-10's positional rule says.
    private fun threaded(
        ctx: Context,
        index: Int,
        tag: Array<String>,
    ): List<String> {
        val roles = ArrayList<String>(2)
        if (index == ctx.rootIndex) roles += ROOT
        if (index == ctx.replyIndex) roles += REPLY
        marker(tag)?.let { if (it !in roles) roles += it }
        if (roles.isEmpty()) roles += MENTION
        return roles
    }

    // Where Quartz's MarkedETag looks for a marker (index 3, then 4, then 2), since clients
    // disagree on whether a relay hint or a pubkey precedes it.
    private fun marker(tag: Array<String>): String? =
        sequenceOf(3, 4, 2).mapNotNull { tag.getOrNull(it) }.firstOrNull { MarkedETag.MARKER.parse(it) != null }

    private fun targetOrContext(
        ctx: Context,
        index: Int,
        name: String,
        targetRole: String,
    ): List<String>? =
        when (name) {
            "e" -> listOf(if (index == ctx.eIndexes.lastOrNull()) targetRole else CONTEXT)
            "a" -> listOf(if (index == ctx.lastA) targetRole else CONTEXT)
            else -> null
        }

    /**
     * Roles the relationship TYPE implies, never stored — the table `docs/schema.md` and
     * `GET /graph/schema` publish. Keyed by (kinds, tag) → role.
     */
    val implied: List<ImpliedRole> =
        listOf(
            ImpliedRole(THREADED_KINDS, "p", MENTION),
            ImpliedRole(setOf(1111, 1244), "E", ROOT),
            ImpliedRole(setOf(1111, 1244), "A", ROOT),
            ImpliedRole(setOf(1111, 1244), "e", REPLY),
            ImpliedRole(setOf(1111, 1244), "a", REPLY),
            ImpliedRole(setOf(1111, 1244), "P", "root_author"),
            ImpliedRole(setOf(1111, 1244), "p", "reply_author"),
            ImpliedRole(null, "q", "quote"),
            ImpliedRole(REPOST_KINDS, "p", "reposted_author"),
            ImpliedRole(REACTION_KINDS, "p", "reacted_author"),
            ImpliedRole(setOf(9734, 9735), "e", "zap"),
            ImpliedRole(setOf(9734, 9735), "a", "zap"),
            ImpliedRole(setOf(9734, 9735), "p", "zapped"),
            ImpliedRole(setOf(9735), "P", ZAPPER),
            ImpliedRole(setOf(5), "e", "delete"),
            ImpliedRole(setOf(5), "a", "delete"),
            ImpliedRole(setOf(1984), "e", "report"),
            ImpliedRole(setOf(1984), "a", "report"),
            ImpliedRole(setOf(1984), "p", "report"),
            ImpliedRole(setOf(1985), "e", "label"),
            ImpliedRole(setOf(1985), "a", "label"),
            ImpliedRole(setOf(1985), "p", "label"),
            ImpliedRole(setOf(3), "p", "follow"),
            ImpliedRole(setOf(10000), "p", "mute"),
            ImpliedRole(setOf(30000, 39089, 39092, 10017, 10020, 10101), "p", "member"),
            ImpliedRole(setOf(10001), "e", "pin"),
            ImpliedRole(setOf(10003, 30001, 30003, 30004, 30005, 30006, 30063, 30267, 10018), "e", "bookmark"),
            ImpliedRole(setOf(10003, 30001, 30003, 30004, 30005, 30063, 30267, 10018), "a", "bookmark"),
            ImpliedRole(setOf(10004, 34550, 4550), "a", "community"),
            ImpliedRole(setOf(34550), "p", "moderator"),
            ImpliedRole(setOf(4550), "e", "approve"),
            ImpliedRole(setOf(8, 30008, 10008), "a", "badge"),
            ImpliedRole(setOf(8), "p", "awarded"),
            ImpliedRole(setOf(30008, 10008), "e", "award"),
            ImpliedRole(setOf(30382, 30383, 30384), "d", "asserts"),
            ImpliedRole(setOf(41), "e", "channel"),
            ImpliedRole(setOf(43), "e", "hide"),
            ImpliedRole(setOf(44), "p", "mute"),
        )

    /** A role the type implies; [kinds] null means any kind. */
    data class ImpliedRole(
        val kinds: Set<Int>?,
        val tag: String,
        val role: String,
    )
}
