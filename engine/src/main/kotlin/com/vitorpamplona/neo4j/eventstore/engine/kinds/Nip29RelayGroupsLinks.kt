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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.OrderProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupAdminsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMembersEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupParticipantsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupPinnedEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupRolesEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.CreateGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.DeleteGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupCreateInviteEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupDeleteEventEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupPutUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupRemoveUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupUpdatePinListEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupJoinRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupLeaveRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.tags.AddressPin
import com.vitorpamplona.quartz.nip29RelayGroups.tags.ChildTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.EventPin
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupPin
import com.vitorpamplona.quartz.nip29RelayGroups.tags.ParentTag

/** Quartz's `nip29RelayGroups` classes. */
internal fun KindMappers.Builder.nip29RelayGroups() {
    // User-signed requests and moderation commands: each names its group in `h`.
    on<CreateGroupEvent> { e -> nip29Groups(e.tags) }
    on<DeleteGroupEvent> { e -> nip29Groups(e.tags) }
    on<GroupCreateInviteEvent> { e -> nip29Groups(e.tags) }
    on<GroupJoinRequestEvent> { e -> nip29Groups(e.tags) }
    on<GroupLeaveRequestEvent> { e -> nip29Groups(e.tags) }
    // A moderator's delete-event: NIP-09's owner-only rule does not govern it, the source kind says so.
    on<GroupDeleteEventEvent> { e ->
        nip29Groups(e.tags)
        each(e.tags, ETag::parse) { event(Relation.DELETED, it, ETag.TAG_NAME) }
    }
    // NIP-29 subgroups: `parent` and `child` are group ids on the same relay, so they target the group as `h` does.
    on<GroupEditMetadataEvent> { e ->
        nip29Groups(e.tags)
        nip29Subgroups(e.tags)
        hashtags(e.tags)
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }
    on<GroupPutUserEvent> { e ->
        nip29Groups(e.tags)
        // NIP-29 put-user: `["p", <pubkey>, <role>…]`, the roles it grants (a relay hint is not one).
        each(e.tags, Nip29RoleTag::parse) { user(Relation.ADDED_USER, it.pubKey, Nip29RoleTag.TAG_NAME, RoleProps(it.roles)) }
    }
    on<GroupRemoveUserEvent> { e ->
        nip29Groups(e.tags)
        each(e.tags, PTag::parse) { user(Relation.REMOVED_USER, it, PTag.TAG_NAME) }
    }
    on<GroupUpdatePinListEvent> { e ->
        nip29Groups(e.tags)
        nip29PinLinks(e.pins())
    }

    // Relay-signed state (39000-39005): the group each belongs to is its own `d`, which restates
    // its ADDRESS, so it is not linked again.

    /*
     * NIP-29 subgroups: `parent` and `child` are group ids on this relay, targeted as `h` is.
     * A Buzz relay writes its channel type as a `t` (`buzzChannelType()`): a type, not a topic.
     */
    on<GroupMetadataEvent> { e ->
        nip29Subgroups(e.tags)
        each(e.tags, HashtagTag::parse) {
            if (it !in GroupMetadataEvent.BUZZ_CHANNEL_TYPES) tag(Relation.HASHTAG, HashtagTag.TAG_NAME, it.lowercase())
        }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }
    // NIP-29 group admins, `["p", <pubkey>, <role>…]`: the roles are relay-defined, so they ride on the link.
    on<GroupAdminsEvent> { e ->
        each(e.tags, Nip29RoleTag::parse) { user(Relation.ADMIN, it.pubKey, Nip29RoleTag.TAG_NAME, RoleProps(it.roles)) }
    }
    // NIP-29 group members (not exhaustive, per the NIP).
    on<GroupMembersEvent> { e -> each(e.tags, PTag::parse) { user(Relation.MEMBER, it, PTag.TAG_NAME) } }
    // NIP-29 LiveKit participants.
    on<GroupParticipantsEvent> { e ->
        each(e.tags, Nip29ParticipantTag::parse) { user(Relation.PARTICIPANT, it, Nip29ParticipantTag.TAG_NAME) }
    }
    // NIP-29 pinned events.
    on<GroupPinnedEvent> { e -> nip29PinLinks(e.pins()) }
    free<GroupRolesEvent>()
}

/** The `h` group a user-signed NIP-29 event is scoped to ([GroupIdTag]) → [Relation.GROUP]. */
private fun LinkBuilder.nip29Groups(tags: TagArray) = each(tags, GroupIdTag::parse) { tag(Relation.GROUP, GroupIdTag.TAG_NAME, it) }

/**
 * NIP-29 subgroups: [ParentTag] → [Relation.PARENT], each [ChildTag] → [Relation.CHILD]. Both
 * hold a group id, so the target is the same `h` node the group's own events scope to; `via`
 * keeps which tag said it.
 */
private fun LinkBuilder.nip29Subgroups(tags: TagArray) {
    each(tags, ParentTag::parse) { tag(Relation.PARENT, GroupIdTag.TAG_NAME, it, ParentTag.TAG_NAME) }
    each(tags, ChildTag::parse) { tag(Relation.CHILD, GroupIdTag.TAG_NAME, it, ChildTag.TAG_NAME) }
}

/**
 * A NIP-29 pin list (the 9010 request and the relay's 39005): `e` and `a` pins interleaved in
 * display order. A graph keeps no edge order, so each [Relation.PIN] carries its position in
 * [pins] as `order`.
 */
private fun LinkBuilder.nip29PinLinks(pins: List<GroupPin>) =
    pins.forEachIndexed { index, pin ->
        val order = OrderProps(index)
        when (pin) {
            is EventPin -> event(Relation.PIN, pin.eventId, EventPin.TAG_NAME, order)
            is AddressPin -> address(Relation.PIN, pin.address, AddressPin.TAG_NAME, order)
        }
    }
