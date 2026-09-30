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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent

/** Quartz's `nip09Deletions` classes. */
internal fun KindMappers.Builder.nip09Deletions() {
    // NIP-09: the `e`/`a` tags are what this request deletes and `k` their kinds. The `p` is
    // Quartz's practice (not in NIP-09): the deleted events' author. Read key-only: no link uses
    // a relay hint, and normalizing one costs a lock on Quartz's global relay-url cache.
    on<DeletionRequestEvent> { e ->
        each(e.tags, ETag::parseId) { event(Relation.DELETED, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parseAddress) { address(Relation.DELETED, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parseKey) { user(Relation.DELETED_AUTHOR, it, PTag.TAG_NAME) }
        each(e.tags, KindTag::parse) { value(Relation.DELETED_KIND, ValueType.KIND, it.toString(), KindTag.TAG_NAME) }
    }
}
