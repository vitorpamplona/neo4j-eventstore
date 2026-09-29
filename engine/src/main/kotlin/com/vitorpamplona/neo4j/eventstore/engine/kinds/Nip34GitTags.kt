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

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip34Git.repository.tags.EucTag
import com.vitorpamplona.quartz.utils.ensure

// Parsers the NIP-34 mappers need that the pinned Quartz lacks.

/**
 * Main's [EucTag.parse] requires the `"euc"` marker, which only the 30617 announcement writes:
 * patches, pull requests and statuses name the target repository by the plain `["r", <commit>]`.
 */
internal object Nip34CommitTag {
    const val TAG_NAME = EucTag.TAG_NAME

    /** The commit of any `r` tag, marked or not. */
    fun parseReference(tag: Array<String>): String? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return tag[1]
    }
}

/**
 * Main has no parser for a repository's `u` tag, naming the repository this one was forked from.
 * It holds either the upstream's announcement address or a plain git URL; only the address form
 * names a repository Nostr knows, so [parse] accepts only that one: `["u", "30617:<pubkey>:<d-tag>"]`.
 */
internal object Nip34UpstreamTag {
    const val TAG_NAME = "u"

    fun parse(tag: Array<String>): ATag? {
        ensure(tag.has(1)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(tag[1].isNotEmpty()) { return null }
        return ATag.parse(tag[1], null)
    }
}
