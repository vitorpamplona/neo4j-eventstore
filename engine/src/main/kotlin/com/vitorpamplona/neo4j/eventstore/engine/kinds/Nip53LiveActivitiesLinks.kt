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
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip53LiveActivities.chat.LiveActivitiesChatMessageEvent
import com.vitorpamplona.quartz.nip53LiveActivities.clip.LiveActivitiesClipEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingRoomEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.tags.MeetingSpaceTag
import com.vitorpamplona.quartz.nip53LiveActivities.nestsServers.NestsServersEvent
import com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.raid.LiveActivitiesRaidEvent
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.LiveActivitiesEvent
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.ParticipantTag
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.tags.PinnedEventTag

/** Quartz's `nip53LiveActivities` classes. */
internal fun KindMappers.Builder.nip53LiveActivities() {
    // NIP-53: the activity's `a` is the chat's ROOT (the spec's example marks it `root`; without a
    // marker, the first `a`), and the `e` it replies to (`reply()`: the `reply`-marked one, else
    // the last unmarked one; Quartz writes the marker) is its PARENT. Any other `e` is a MENTION:
    // a message has one parent, so a `root` + `reply` pair must not give two. The `p` naming the
    // author the parent tag names (its pubkey slot) is the PARENT_AUTHOR, the rest MENTIONs, as on
    // kinds 1 and 42.
    on<LiveActivitiesChatMessageEvent> { e ->
        val activities = e.tags.mapNotNull(Nip53ActivityTag::parse)
        val activity = activities.firstOrNull { it.isRoot() } ?: activities.firstOrNull()
        activities.forEach { address(if (it === activity) Relation.ROOT else Relation.MENTION, it.activity, Nip53ActivityTag.TAG_NAME) }
        val parentTag = e.reply()
        val parentAuthor = parentTag?.author
        event(Relation.PARENT, parentTag, MarkedETag.TAG_NAME)
        each(e.tags, ETag::parse) { if (it.eventId != parentTag?.eventId) event(Relation.MENTION, it, ETag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(if (it.pubKey == parentAuthor) Relation.PARENT_AUTHOR else Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        hashtags(e.tags)
        contentMentions(e.citedNIP19())
    }

    // The clipped stream, its host (not necessarily the stream's signer, which may be a provider) and the clip's video URL (`r`).
    on<LiveActivitiesClipEvent> { e ->
        address(Relation.CLIPPED, e.activity(), ATag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.CLIPPED_AUTHOR, it, PTag.TAG_NAME) }
        each(e.tags, ReferenceTag::parse) { tag(Relation.TAG, ReferenceTag.TAG_NAME, it) }
    }

    // NIP-53: a meeting's `a` is the space (30312) it takes place in.
    on<MeetingRoomEvent> { e ->
        address(Relation.PARENT, e.interactiveRoom()?.address, MeetingSpaceTag.TAG_NAME)
        nip53ParticipantLinks(e.tags)
        each(e.tags, PinnedEventTag::parse) { event(Relation.PIN, it, PinnedEventTag.TAG_NAME) }
    }

    on<MeetingSpaceEvent> { e -> nip53ParticipantLinks(e.tags) }

    // NIP-53 presence: `["a", <room>, <relay>, "root"]`. Quartz writes it without the marker, so the room is the `a` either way.
    on<MeetingRoomPresenceEvent> { e -> address(Relation.ROOT, e.interactiveRoom()?.address, MeetingSpaceTag.TAG_NAME) }

    // The `root`-marked stream is the one raiding, the `mention`-marked one its target.
    on<LiveActivitiesRaidEvent> { e ->
        address(Relation.ROOT, e.fromActivity(), ATag.TAG_NAME)
        address(Relation.RAIDED, e.toActivity(), ATag.TAG_NAME)
    }

    // NIP-53: participants with their roles, pinned chat messages, the NIP-75 zap goal the stream
    // raises toward (zap.stream's `goal`), and the stream's `t` topics.
    on<LiveActivitiesEvent> { e ->
        nip53ParticipantLinks(e.tags)
        each(e.tags, PinnedEventTag::parse) { event(Relation.PIN, it, PinnedEventTag.TAG_NAME) }
        each(e.tags, Nip53GoalTag::parse) { event(Relation.GOAL, it, Nip53GoalTag.TAG_NAME) }
        hashtags(e.tags)
    }

    free<NestsServersEvent>()
}

/**
 * NIP-53 participants: `["p", <pubkey>, <relay>, <role>, <proof>]`. The role (Host, Speaker,
 * Moderator…) and the proof of agreement to participate ride on the link as written.
 */
private fun LinkBuilder.nip53ParticipantLinks(tags: TagArray) =
    each(tags, ParticipantTag::parse) { user(Relation.PARTICIPANT, it, ParticipantTag.TAG_NAME, it.nip53LinkProps()) }
