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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.CreditProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ParticipantProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ViewProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip71Video.AddressableVideoEvent
import com.vitorpamplona.quartz.nip71Video.RegularVideoEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip71Video.credits.CreditTarget
import com.vitorpamplona.quartz.nip71Video.tags.TextTrackTag
import com.vitorpamplona.quartz.nip71Video.textTrack.LanguageTag
import com.vitorpamplona.quartz.nip71Video.textTrack.TextTrackEvent
import com.vitorpamplona.quartz.nip71Video.views.VideoViewEvent

/** Quartz's `nip71Video` classes. */
internal fun KindMappers.Builder.nip71Video() {
    // The four video kinds (21, 22, 34235, 34236) link alike: registered once at their two bases.
    on<RegularVideoEvent> { e -> nip71VideoLinks(e, e.tags) }
    on<AddressableVideoEvent> { e -> nip71VideoLinks(e, e.tags) }

    // The video this track belongs to and its language (`l`).
    on<TextTrackEvent> { e ->
        each(e.tags, ATag::parse) { address(Relation.VIDEO, it, ATag.TAG_NAME) }
        each(e.tags, LanguageTag::parse) { tag(Relation.TAG, LanguageTag.TAG_NAME, it) }
    }

    // The watched video: its address, and the id of the exact version played. Each carries the
    // session `phase` (start / end) when there is one, so a view count is the `start`s.
    on<VideoViewEvent> { e ->
        val props = e.phase()?.let { ViewProps(it.code) }
        address(Relation.VIEWED, e.video(), ATag.TAG_NAME, props)
        event(Relation.VIEWED, e.videoVersion(), ETag.TAG_NAME, props)
    }
}

/**
 * The links every NIP-71 video kind (21, 22, 34235, 34236) shares.
 * NIP-71 defines `p` as "a participant in the video". divine.video labels its references in the
 * marker slot (see Quartz's `VideoCredits`): `mention` is a passing mention, `inspired-by` and
 * the labels on `a`/`e` (`audio`…) are credits, and any other label on a `p` is the participant's
 * role. The `text-track` names its captions either as a URL (not modelled) or as an event or a
 * 39307 address.
 */
private fun LinkBuilder.nip71VideoLinks(
    video: VideoEvent,
    tags: TagArray,
) {
    video.credits().forEach { credit ->
        val label = credit.label
        when (val target = credit.target) {
            is CreditTarget.Person -> {
                when (label) {
                    null -> user(Relation.PARTICIPANT, target.pubKey, PTag.TAG_NAME)
                    NIP71_MENTION_LABEL -> user(Relation.MENTION, target.pubKey, PTag.TAG_NAME)
                    NIP71_INSPIRED_BY_LABEL -> user(Relation.CREDITED, target.pubKey, PTag.TAG_NAME, CreditProps(label))
                    else -> user(Relation.PARTICIPANT, target.pubKey, PTag.TAG_NAME, ParticipantProps(listOfNotNull(label)))
                }
            }

            is CreditTarget.Video -> {
                if (label == null || label == NIP71_MENTION_LABEL) {
                    address(Relation.MENTION, target.address, ATag.TAG_NAME)
                } else {
                    address(Relation.CREDITED, target.address, ATag.TAG_NAME, CreditProps(label))
                }
            }

            is CreditTarget.Event -> {
                if (label == NIP71_MENTION_LABEL) {
                    event(Relation.MENTION, target.eventId, ETag.TAG_NAME)
                } else {
                    event(Relation.CREDITED, target.eventId, ETag.TAG_NAME, CreditProps(label))
                }
            }
        }
    }
    video.textTrack().forEach {
        address(Relation.TEXT_TRACK, it.nip71Address(), TextTrackTag.TAG_NAME)
        event(Relation.TEXT_TRACK, it.nip71EventId(), TextTrackTag.TAG_NAME)
    }
    hashtags(tags)
}
