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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.FoundProps
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nipCCGeocaching.curation.GeocacheCurationListEvent
import com.vitorpamplona.quartz.nipCCGeocaching.foundLog.GeocacheFoundLogEvent
import com.vitorpamplona.quartz.nipCCGeocaching.foundLog.tags.GeocacheTag
import com.vitorpamplona.quartz.nipCCGeocaching.listing.GeocacheListingEvent
import com.vitorpamplona.quartz.nipCCGeocaching.listing.tags.FirstToFindWinnerTag
import com.vitorpamplona.quartz.nipCCGeocaching.listing.tags.VerificationKeyTag
import com.vitorpamplona.quartz.nipCCGeocaching.verification.GeocacheVerificationEvent
import com.vitorpamplona.quartz.nipCCGeocaching.verification.tags.FinderCacheTag

/** Quartz's `nipCCGeocaching` classes. */
internal fun KindMappers.Builder.nipCCGeocaching() {
    on<GeocacheCurationListEvent> { e ->
        e.geocaches().forEach { address(Relation.CURATED, it, ATag.TAG_NAME) }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }

    // NIP-CC: the cache found. Whether the log carries its cache's verification rides on the link; the verification itself is an embedded event, not a reference.
    on<GeocacheFoundLogEvent> { e ->
        address(Relation.FOUND, e.geocache(), GeocacheTag.TAG_NAME, FoundProps(verified = e.hasVerificationAttached()))
    }

    // NIP-CC: the locked-in first-to-find winner (`F`), the key that signs verifications, the cache
    // type as `t` and its geohashes. Its `r` tags are relays for logs, not web references.
    on<GeocacheListingEvent> { e ->
        user(Relation.WINNER, e.firstToFindWinner(), FirstToFindWinnerTag.TAG_NAME)
        user(Relation.VERIFIER, e.verificationKey(), VerificationKeyTag.TAG_NAME)
        hashtags(e.tags)
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }

    // NIP-CC's `a` here is the composite `<finder>:<naddr>`, not a NIP-01 address: it names both the finder and the cache.
    on<GeocacheVerificationEvent> { e ->
        val finderCache = e.finderCache() ?: return@on
        user(Relation.FINDER, finderCache.finderPubKey, FinderCacheTag.TAG_NAME)
        address(Relation.VERIFIED, finderCache.cache, FinderCacheTag.TAG_NAME)
    }
}
