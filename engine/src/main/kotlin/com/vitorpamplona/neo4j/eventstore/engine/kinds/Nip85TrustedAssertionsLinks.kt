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
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyIdentifierTag
import com.vitorpamplona.quartz.nip85TrustedAssertions.addressables.AddressableAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.events.EventAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.externalIds.ExternalIdAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.TrustProviderListEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent

/** Quartz's `nip85TrustedAssertions` classes. */
internal fun KindMappers.Builder.nip85TrustedAssertions() {
    // NIP-85: the `d` is the SUBJECT, the address this assertion scores (not the assertion's own
    // identity), with the scores as props. An `a` equal to the `d` is only its relay hint.
    on<AddressableAssertionEvent> { e -> address(Relation.SUBJECT, e.aboutAddress(), DTag.TAG_NAME, e.tags.nip85ContentSubjectProps()) }

    // NIP-85: the `d` is the SUBJECT, the event this assertion scores (not the assertion's own
    // identity), with the scores as props. An `e` equal to the `d` is only its relay hint.
    on<EventAssertionEvent> { e -> event(Relation.SUBJECT, e.aboutEvent(), DTag.TAG_NAME, e.tags.nip85ContentSubjectProps()) }

    // NIP-85: the `d` is the SUBJECT, the NIP-73 identifier this assertion scores (the same node a
    // NIP-73 `i` names), with the scores as props; the `k` tags are its NIP-73 kinds.
    on<ExternalIdAssertionEvent> { e ->
        tag(Relation.SUBJECT, ReplyIdentifierTag.TAG_NAME, e.aboutExternalId(), DTag.TAG_NAME, e.tags.nip85ContentSubjectProps())
        each(e.tags, Nip85ExternalIdKindTag::parse) { tag(Relation.TAG, Nip85ExternalIdKindTag.TAG_NAME, it) }
    }

    // NIP-85: each `<kind>:<tag>` entry names the pubkey the user trusts to sign that assertion,
    // one `SERVICE_PROVIDER` link per entry with the `service` it provides (`30382:rank`). The
    // entry's relay is only where to fetch from: an entry without one still states the trust.
    on<TrustProviderListEvent> { e ->
        each(e.tags, Nip85ServiceProviderTag::parse) { user(Relation.SERVICE_PROVIDER, it.pubkey, it.tagName(), it.linkProps()) }
    }

    // NIP-85: the `d` is the SUBJECT, the user this card is about (not the card's own identity,
    // so it is linked although it is the `d`). The public scores ride on it (`nip85SubjectProps`,
    // keyed by their NIP-85 tag names: `rank`, `followers`, `hops`, …); `t` are the user's topics.
    // A `p` equal to the `d` is only its relay hint. The encrypted contact-card fields are invisible.
    on<UserAssertionEvent> { e ->
        user(Relation.SUBJECT, e.aboutUser(), DTag.TAG_NAME, e.nip85SubjectProps())
        hashtags(e.tags)
    }
}
