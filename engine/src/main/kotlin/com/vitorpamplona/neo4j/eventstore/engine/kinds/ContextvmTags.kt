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

import com.vitorpamplona.quartz.contextvm.core.CvmTags
import com.vitorpamplona.quartz.nip01Core.core.has

/**
 * CEP-15's NIP-73 `["i", "<schema-hash>", "<tool-name>"]`: a common schema a server implements.
 * Main's Quartz has no parser for it (only `CommonToolSchema`'s bulk reader of the content).
 */
internal object ContextvmSchemaHashTag {
    const val TAG_NAME = CvmTags.EXTERNAL_ID

    /** The schema hash, the identity two equivalent servers share. */
    fun parse(tag: Array<String>): String? {
        if (!tag.has(1) || tag[0] != TAG_NAME || tag[1].isBlank()) return null
        return tag[1]
    }
}

/**
 * CEP-15's NIP-73 `["k", "io.contextvm/common-schema"]`: the namespace the event's `i` tags live
 * in. A string, not a Nostr kind number, so not the NIP-01 `KindTag`. Main's Quartz has no parser for it.
 */
internal object ContextvmSchemaNamespaceTag {
    const val TAG_NAME = CvmTags.EXTERNAL_KIND

    fun parse(tag: Array<String>): String? {
        if (!tag.has(1) || tag[0] != TAG_NAME || tag[1].isBlank()) return null
        return tag[1]
    }
}
