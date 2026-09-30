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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.NoProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.VoteProps
import com.vitorpamplona.quartz.buzz.cwChannelWindow.ThreadSummaryEvent
import com.vitorpamplona.quartz.buzz.cwChannelWindow.WindowBoundsEvent
import com.vitorpamplona.quartz.buzz.dm.DmAddMemberEvent
import com.vitorpamplona.quartz.buzz.dm.DmCreatedEvent
import com.vitorpamplona.quartz.buzz.dm.DmHideEvent
import com.vitorpamplona.quartz.buzz.dm.DmOpenEvent
import com.vitorpamplona.quartz.buzz.dvDmVisibility.DmVisibilityEvent
import com.vitorpamplona.quartz.buzz.erReminders.EventReminderEvent
import com.vitorpamplona.quartz.buzz.forum.ForumCommentEvent
import com.vitorpamplona.quartz.buzz.forum.ForumPostEvent
import com.vitorpamplona.quartz.buzz.forum.ForumVoteEvent
import com.vitorpamplona.quartz.buzz.plPushLease.PushLeaseEvent
import com.vitorpamplona.quartz.buzz.presence.PresenceUpdateEvent
import com.vitorpamplona.quartz.buzz.presence.TypingIndicatorEvent
import com.vitorpamplona.quartz.buzz.stream.CanvasEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageBookmarkedEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageDiffEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageEditEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessagePinnedEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageScheduledEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageV2Event
import com.vitorpamplona.quartz.buzz.stream.StreamReminderEvent
import com.vitorpamplona.quartz.buzz.stream.SystemMessageEvent
import com.vitorpamplona.quartz.buzz.stream.sidecars.ChannelSummaryEvent
import com.vitorpamplona.quartz.buzz.stream.sidecars.PresenceSnapshotEvent
import com.vitorpamplona.quartz.buzz.stream.tags.LanguageTag
import com.vitorpamplona.quartz.buzz.teams.TeamEvent
import com.vitorpamplona.quartz.buzz.threading.buzzThreadReply
import com.vitorpamplona.quartz.buzz.threading.buzzThreadRoot
import com.vitorpamplona.quartz.buzz.wpWorkspaceProfile.SetWorkspaceProfileEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag

/**
 * Quartz's `buzz` classes: the channel, message, forum, DM and presence kinds here; the agent,
 * job, workflow and huddle kinds in [buzzAgents] (`BuzzAgentLinks.kt`); the identity-archival,
 * moderation and relay-admin commands in [buzzModeration] (`BuzzModerationLinks.kt`).
 */
internal fun KindMappers.Builder.buzz() {
    buzzMessaging()
    buzzAgents()
    buzzModeration()
}

/**
 * The channels a Buzz event's `h` tags ([GroupIdTag]) name, as [Relation.GROUP] (or [relation]).
 * Buzz scopes nearly every kind to a channel this way, and a channel id is a NIP-29 group id, so
 * the target is the same `h` value node the NIP-29 kinds link to.
 */
internal fun LinkBuilder.buzzChannels(
    tags: TagArray,
    relation: Relation<NoProps> = Relation.GROUP,
) = each(tags, GroupIdTag::parse) { value(relation, ValueType.GROUP, it, GroupIdTag.TAG_NAME) }

/**
 * ROOT and PARENT from Buzz's thread e-tags (`buzzThread`). A direct reply carries only a
 * `reply` marker, because its root IS its parent, so a lone `reply` is both the ROOT and the
 * PARENT; a nested reply carries both markers.
 */
internal fun LinkBuilder.buzzThreadLinks(
    root: HexKey?,
    reply: HexKey?,
) {
    event(Relation.ROOT, root ?: reply, ETag.TAG_NAME)
    event(Relation.PARENT, reply, ETag.TAG_NAME)
}

/** [buzzThreadLinks] from the marked `e` tags of a stream message (40002) or a forum comment (45003). */
internal fun LinkBuilder.buzzThreadLinks(tags: TagArray) = buzzThreadLinks(tags.buzzThreadRoot(), tags.buzzThreadReply())

private fun KindMappers.Builder.buzzMessaging() {
    // Stream messages
    on<StreamMessageV2Event> { e ->
        buzzChannels(e.tags)
        buzzThreadLinks(e.tags)
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        contentMentions(e.content)
    }
    on<StreamMessageEditEvent> { e ->
        buzzChannels(e.tags)
        event(Relation.EDITED, e.editedMessage(), ETag.TAG_NAME)
        contentMentions(e.content)
    }
    on<StreamMessagePinnedEvent> { e ->
        buzzChannels(e.tags)
        event(Relation.PIN, e.pinnedMessage(), ETag.TAG_NAME)
    }
    on<StreamMessageBookmarkedEvent> { e ->
        buzzChannels(e.tags)
        event(Relation.BOOKMARK, e.bookmarkedMessage(), ETag.TAG_NAME)
    }
    on<StreamMessageScheduledEvent> { e ->
        buzzChannels(e.tags)
        contentMentions(e.content)
    }
    on<StreamReminderEvent> { e ->
        buzzChannels(e.tags)
        each(e.tags, PTag::parse) { user(Relation.RECIPIENT, it, PTag.TAG_NAME) }
        event(Relation.REMINDED, e.targetMessage(), ETag.TAG_NAME)
    }
    // Buzz reuses `l` for the diff's programming language (not a NIP-32 label).
    on<StreamMessageDiffEvent> { e ->
        buzzChannels(e.tags)
        each(e.tags, LanguageTag::parse) { value(Relation.LANGUAGE, ValueType.LANGUAGE, it, LanguageTag.TAG_NAME) }
    }
    on<CanvasEvent> { e ->
        buzzChannels(e.tags)
        contentMentions(e.content)
    }
    // Only the channel: the actor, target and deleted message live in the content JSON, which
    // links do not parse.
    on<SystemMessageEvent> { e -> buzzChannels(e.tags) }

    // Relay sidecars and channel windows
    on<ChannelSummaryEvent> { e -> buzzChannels(e.tags) }
    free<PresenceSnapshotEvent>()
    on<WindowBoundsEvent> { e -> buzzChannels(e.tags) }
    // The summary is ABOUT the thread root its `e` names; it is not part of that thread, so it is
    // not a ROOT link. The `d` repeats the root and is not linked (rule: no links from `d`).
    on<ThreadSummaryEvent> { e ->
        buzzChannels(e.tags)
        event(Relation.ABOUT, e.rootEventTag(), ETag.TAG_NAME)
    }

    // Forum
    on<ForumPostEvent> { e ->
        buzzChannels(e.tags)
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        contentMentions(e.content)
    }
    on<ForumCommentEvent> { e ->
        buzzChannels(e.tags)
        buzzThreadLinks(e.tags)
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        contentMentions(e.content)
    }
    on<ForumVoteEvent> { e ->
        buzzChannels(e.tags)
        event(Relation.VOTED, e.target(), ETag.TAG_NAME, VoteProps(e.direction()?.code))
    }

    // Direct messages
    on<DmOpenEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.PARTICIPANT, it, PTag.TAG_NAME) }
    }
    // The DM id rides in `d` on a regular kind, so it is a reference, not this event's address:
    // it is linked as the `h` group every other DM event scopes itself with.
    on<DmCreatedEvent> { e ->
        value(Relation.GROUP, ValueType.GROUP, e.dmId(), DTag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.PARTICIPANT, it, PTag.TAG_NAME) }
    }
    on<DmAddMemberEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.ADDED_USER, e.member(), PTag.TAG_NAME)
    }
    // The `h` here is the DM being hidden, the object of the command, not its scope.
    on<DmHideEvent> { e -> buzzChannels(e.tags, Relation.HIDDEN) }
    // Each `h` is a DM the viewer has hidden, not a scope.
    on<DmVisibilityEvent> { e ->
        user(Relation.VIEWER, e.viewerFromPTag(), PTag.TAG_NAME)
        buzzChannels(e.tags, Relation.HIDDEN)
    }

    // Presence
    // The relay-synthesized read form names whose presence it reports in a `p`; a
    // client-published update carries none, since its subject is its author.
    on<PresenceUpdateEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.SUBJECT, it, PTag.TAG_NAME) }
    }
    on<TypingIndicatorEvent> { e ->
        buzzChannels(e.tags)
        buzzThreadLinks(e.threadRootId(), e.threadReplyId())
    }

    // Settings, reminders and profiles: nothing they reference is a graph node.
    free<EventReminderEvent>()
    free<PushLeaseEvent>()
    free<TeamEvent>()
    free<SetWorkspaceProfileEvent>()
}
