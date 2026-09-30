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
import com.vitorpamplona.quartz.contextvm.cep06Announcements.CvmServerAnnouncementEvent
import com.vitorpamplona.quartz.contextvm.cep06Announcements.CvmToolsListEvent

/** Quartz's `contextvm` classes. */
internal fun KindMappers.Builder.contextvm() {
    free<CvmServerAnnouncementEvent>()

    // CEP-15 common tool schemas, written NIP-73 style: `["i", <schema-hash>, <tool>]` and
    // `["k", "io.contextvm/common-schema"]`. The tool list itself is JSON content.
    on<CvmToolsListEvent> { e ->
        each(e.tags, ContextvmSchemaHashTag::parse) { value(Relation.SCHEMA, ValueType.SCHEMA_HASH, it, ContextvmSchemaHashTag.TAG_NAME) }
        each(e.tags, ContextvmSchemaNamespaceTag::parse) {
            value(Relation.SCHEMA_NAMESPACE, ValueType.SCHEMA_NAMESPACE, it, ContextvmSchemaNamespaceTag.TAG_NAME)
        }
    }
}
