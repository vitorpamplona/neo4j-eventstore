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
package com.vitorpamplona.neo4j.eventstore.engine.vocab

import com.vitorpamplona.quartz.utils.Rfc3986

/**
 * What a link may point at. Each [Relation] declares its targets, and [LinkBuilder] refuses a
 * link to anything else: a relationship type then tells a query what can be at its other end,
 * and the relation catalog (`docs/relations.md`, checked by a test) is read off these
 * declarations rather than written beside them.
 */
sealed interface Target {
    /** How the relation catalog writes this target. */
    val code: String
}

/** The three node kinds a link can point at besides a value. */
enum class NodeTarget(
    override val code: String,
) : Target {
    EVENT("event"),
    ADDRESS("address"),
    USER("user"),
}

/**
 * What a value IS: the type half of a `:Tag` node's key (`hashtag:nostr`, `url:https://…`,
 * `kind:1`). A value is keyed by its meaning, not by the tag letter it was written in: an `r`
 * URL and a NIP-73 `i` URL are one node, and so are a `t` hashtag and a NIP-73 `#hashtag`.
 */
enum class ValueType(
    override val code: String,
    /** What the value is, as the relation catalog (`docs/relations.md`) says it. */
    val description: String,
) : Target {
    HASHTAG("hashtag", "A topic: a `t` tag or a NIP-73 `#` id, lowercased."),

    URL("url", "A web resource: an `r` tag, a NIP-73 `http(s)` id, a URL input."),

    GEOHASH("geohash", "A place: a `g` geohash or a NIP-73 `geo:` id."),

    EXTERNAL("external", "Any other NIP-73 external content id (`isbn:…`, `podcast:guid:…`, `doi:…`), as written."),

    KIND("kind", "An event kind: a number, or a NIP-73 kind (`web`, `isbn`) where a NIP-22 `K`/`k` names one."),

    LANGUAGE("language", "An ISO 639 language code."),

    LABEL("label", "A NIP-32 label (`l`)."),

    LABEL_NAMESPACE("label_namespace", "A NIP-32 label namespace (`L`)."),

    GROUP("group", "A group or channel id (NIP-29 `h`, a Marmot or Buzz channel)."),

    WORD("word", "A muted word."),

    IDENTITY("identity", "A NIP-39 external identity, `platform:identity`."),

    SHA256("sha256", "The SHA-256 of a blob (a reported media file, NIP-56 `x`)."),

    GIT_COMMIT("git_commit", "A git commit id: the earliest unique commit NIP-34 names a repository by."),

    TORRENT("btih", "A BitTorrent info hash."),

    KEY_PACKAGE_REF("key_package_ref", "A Marmot KeyPackageRef."),

    SCHEMA_HASH("schema_hash", "A ContextVM schema hash."),

    SCHEMA_NAMESPACE("schema_namespace", "A ContextVM schema namespace."),

    APP("app", "An application identifier (a software asset's `i`)."),

    WIKI("wiki", "An unresolved wiki link: the target slug as written."),

    LIST("list", "A list named but not declared (a parent list by name)."),

    OBJECT("object", "An opaque object id an audit entry names (a channel UUID, a media hash)."),
    ;

    /**
     * [value] in the one form that keys its node, so every tag and every NIP-73 id that names the
     * same thing reaches the same node: hashtags and geohashes lowercase (NIP-73 writes them so),
     * web URLs normalized without their fragment (NIP-73's form for a URL id, which Quartz's
     * `UrlId` writes through the same [Rfc3986]). Only `http(s)`: [Rfc3986] reads any other
     * scheme as a web address (`spotify:search:x` became `https://x/`), so an `r` holding
     * another URI, or a URL that does not parse, is kept as written.
     */
    fun normalize(value: String): String =
        when (this) {
            HASHTAG, GEOHASH -> value.lowercase()
            URL -> if (isWebUrl(value)) runCatching { Rfc3986.normalizeAndRemoveFragment(value) }.getOrNull() ?: value else value
            else -> value
        }

    companion object {
        private fun isWebUrl(value: String) =
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

        /** The types a NIP-73 external content id can resolve to ([LinkBuilder.external]). */
        val EXTERNAL_CONTENT: Array<Target> = arrayOf(URL, EXTERNAL, HASHTAG, GEOHASH)

        /**
         * A NIP-73 id as the value it names: `http(s)://…` is a [URL], `#tag` a [HASHTAG],
         * `geo:cell` a [GEOHASH], anything else [EXTERNAL] as written. Null when blank.
         */
        fun ofExternal(id: String?): Pair<ValueType, String>? {
            if (id.isNullOrBlank()) return null
            return when {
                isWebUrl(id) -> {
                    URL to id
                }

                id.startsWith("#") -> {
                    id
                        .substring(1)
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                        ?.let { HASHTAG to it }
                }

                id.startsWith("geo:", ignoreCase = true) -> {
                    id
                        .substring(4)
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                        ?.let { GEOHASH to it }
                }

                else -> {
                    EXTERNAL to id
                }
            }
        }
    }
}
