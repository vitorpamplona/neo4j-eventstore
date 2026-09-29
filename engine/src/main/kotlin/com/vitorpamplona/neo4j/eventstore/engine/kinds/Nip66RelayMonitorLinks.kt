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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.RelayDiscoveryEvent
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.tags.AcceptedKindTag
import com.vitorpamplona.quartz.nip66RelayMonitor.monitor.RelayMonitorEvent

/** Quartz's `nip66RelayMonitor` classes. */
internal fun KindMappers.Builder.nip66RelayMonitor() {
    /*
     * NIP-66: the relay itself is this event's `d` (a URL, not linked); its topics, geohashes and
     * accepted kinds are. A negated `k` is a kind the relay rejects, so it is not a `k` it has.
     */
    on<RelayDiscoveryEvent> { e ->
        hashtags(e.tags)
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
        each(e.tags, AcceptedKindTag::parse) { if (!it.negated) tag(Relation.TAG, AcceptedKindTag.TAG_NAME, it.kind.toString()) }
    }
    on<RelayMonitorEvent> { e -> each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) } }
}
