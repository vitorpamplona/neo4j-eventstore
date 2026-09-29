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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ItemProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.MemberProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.OrderProps
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.AddressableListHeaderEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.ListHeaderEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.ConceptGraphTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.AddressableListItemEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.ListItemEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.ElementOfTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.ParentList
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.ParentListTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.SubsetOfTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.taggings.tags.PolarityTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.tags.InheritFromTag
import com.vitorpamplona.quartz.experimental.interactiveStories.InteractiveStoryBaseEvent
import com.vitorpamplona.quartz.experimental.interactiveStories.InteractiveStoryReadingStateEvent
import com.vitorpamplona.quartz.experimental.interactiveStories.tags.RootSceneTag
import com.vitorpamplona.quartz.experimental.interactiveStories.tags.StoryOptionTag
import com.vitorpamplona.quartz.experimental.library.BlossomPieceIndexEvent
import com.vitorpamplona.quartz.experimental.library.BookshelfDirectoryEvent
import com.vitorpamplona.quartz.experimental.library.LearningResourceEvent
import com.vitorpamplona.quartz.experimental.music.playlist.MusicPlaylistEvent
import com.vitorpamplona.quartz.experimental.music.track.MusicTrackEvent
import com.vitorpamplona.quartz.experimental.publications.PublicationContentEvent
import com.vitorpamplona.quartz.experimental.publications.PublicationIndexEvent
import com.vitorpamplona.quartz.experimental.publications.tags.WikilinkTag
import com.vitorpamplona.quartz.experimental.trustedLists.TrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.addressables.AddressableTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.addressables.tags.AddressMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.events.EventTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.events.tags.EventMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.externalIds.ExternalIdTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.externalIds.tags.ExternalIdMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.tags.ObserverTag
import com.vitorpamplona.quartz.experimental.trustedLists.tags.SourceTag
import com.vitorpamplona.quartz.experimental.trustedLists.tags.TrustedListMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.users.UserTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.users.tags.PubKeyMemberTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootEventTag

/** Quartz's `experimental` list-shaped classes: decentralized and trusted lists, libraries, playlists, publications, stories. */
internal fun KindMappers.Builder.experimentalLists() {
    // The `b` inherit-from targets and the Concept Graph node, only when the `concept-graph` tag
    // is written: `conceptGraph`'s computed fallback is derived from this header's own address.
    on<AddressableListHeaderEvent> { e ->
        each(e.tags, InheritFromTag::parse) { address(Relation.INHERIT_FROM, it.target, InheritFromTag.TAG_NAME) }
        each(e.tags, ConceptGraphTag::parse) { address(Relation.CONCEPT_GRAPH, it, ConceptGraphTag.TAG_NAME) }
    }
    free<ListHeaderEvent>()

    // The list(s) this item is on and the item itself: see [experimentalListItemLinks].
    on<ListItemEvent> { e -> experimentalListItemLinks(e.tags) }

    // The item tags ([experimentalListItemLinks]), plus Tapestry's class-thread claims (`b`
    // inherit-from, `n` element-of, `s` subset-of) and the `q` a curation copy points back to its
    // original with.
    //
    // Taggings overload the item slots (a PubKeyTagging's `a` is the tag applied, its `p` the
    // target) and only deployment-configured `z` namespaces tell them apart, so every item slot
    // links as ITEM and a tagging's `polarity` rides on them. The `curation-method` is JSON, not
    // modelled.
    on<AddressableListItemEvent> { e ->
        val itemProps = e.tags.firstNotNullOfOrNull(PolarityTag::parseValue)?.let { ItemProps(polarity = it) }
        experimentalListItemLinks(e.tags, itemProps)
        each(e.tags, InheritFromTag::parse) { address(Relation.INHERIT_FROM, it.target, InheritFromTag.TAG_NAME) }
        each(e.tags, ElementOfTag::parse) { address(Relation.ELEMENT_OF, it, ElementOfTag.TAG_NAME) }
        each(e.tags, SubsetOfTag::parse) { address(Relation.SUBSET_OF, it, SubsetOfTag.TAG_NAME) }
        quotes(e.tags)
    }

    // Members are pubkeys (`p`).
    on<UserTrustedListEvent> { e ->
        each(e.tags, PubKeyMemberTag::parse) { user(Relation.MEMBER, it, PubKeyMemberTag.TAG_NAME, it.memberProps()) }
        experimentalTrustedListLinks(e, PubKeyMemberTag.TAG_NAME)
    }

    // Members are event ids (`e`).
    on<EventTrustedListEvent> { e ->
        each(e.tags, EventMemberTag::parse) { event(Relation.MEMBER, it.eventId, EventMemberTag.TAG_NAME, it.memberProps()) }
        experimentalTrustedListLinks(e, EventMemberTag.TAG_NAME)
    }

    // Members are addresses (`a`).
    on<AddressableTrustedListEvent> { e ->
        each(e.tags, AddressMemberTag::parse) { address(Relation.MEMBER, it.address, AddressMemberTag.TAG_NAME, it.memberProps()) }
        experimentalTrustedListLinks(e, AddressMemberTag.TAG_NAME)
    }

    // Members are NIP-73 external ids (`i`).
    on<ExternalIdTrustedListEvent> { e ->
        each(e.tags, ExternalIdMemberTag::parse) {
            tag(Relation.MEMBER, ExternalIdMemberTag.TAG_NAME, it.externalId, ExternalIdMemberTag.TAG_NAME, it.memberProps())
        }
        experimentalTrustedListLinks(e, ExternalIdMemberTag.TAG_NAME)
    }

    // The whole file's url (`r`). Hashes (`x`, `b`) and servers are values, not links.
    on<BlossomPieceIndexEvent> { e -> tag(Relation.TAG, ReferenceTag.TAG_NAME, e.url()) }

    // What is on the shelf, `a` and `e` entries alike (`items`).
    on<BookshelfDirectoryEvent> { e ->
        e.items().forEach { item ->
            if (item.address !=
                null
            ) {
                address(Relation.MEMBER, item.address, ATag.TAG_NAME)
            } else {
                event(Relation.MEMBER, item.eventId, ETag.TAG_NAME)
            }
        }
    }

    on<LearningResourceEvent> { e -> hashtags(e.tags) }

    // The tracks, as a curated set in playlist order (`order`, from 0). `a` tags to other kinds
    // are kept by `edit` but mean nothing here.
    on<MusicPlaylistEvent> { e ->
        e.trackAddresses().forEachIndexed { order, track -> address(Relation.CURATED, track, ATag.TAG_NAME, OrderProps(order)) }
        hashtags(e.tags)
    }

    on<MusicTrackEvent> { e -> hashtags(e.tags) }

    // The table of contents (`sections`: `a` and `e` entries in display order) as members, each
    // with its `order` (from 0), `level` and inline `title`; the `p` tags (NKBIP-01 does not say
    // whose), the topics, and the original a derivative work names in uppercase `A` / `E`.
    on<PublicationIndexEvent> { e ->
        e.sections().forEachIndexed { order, section ->
            val props = MemberProps(order = order, level = section.level, title = section.title)
            if (section.address != null) {
                address(Relation.MEMBER, section.address, ATag.TAG_NAME, props)
            } else {
                event(Relation.MEMBER, section.eventId, ETag.TAG_NAME, props)
            }
        }
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        hashtags(e.tags)
        // A derivative work names its original the way a NIP-22 scope does: uppercase `A` / `E`.
        each(e.tags, RootAddressTag::parseAddressId) { address(Relation.SOURCE, it, RootAddressTag.TAG_NAME) }
        each(e.tags, RootEventTag::parseKey) { event(Relation.SOURCE, it, RootEventTag.TAG_NAME) }
    }

    // The index this section belongs to, rebuilt from its bare identifier (`T`, else `c`) and this
    // event's own author (`publicationAddress`), so an inference; and each `wikilink`: its event
    // when the tag names one, else its target slug as written, plus the linked event's author.
    on<PublicationContentEvent> { e ->
        address(Relation.PUBLICATION, e.publicationAddress(), experimentalPublicationTagName(e))
        e.wikilinks().forEach { link ->
            if (link.eventId != null) {
                event(Relation.WIKILINK, link.eventId, WikilinkTag.TAG_NAME)
            } else {
                tag(Relation.WIKILINK, WikilinkTag.TAG_NAME, link.target)
            }
            user(Relation.WIKILINK_AUTHOR, link.pubKey, WikilinkTag.TAG_NAME)
        }
    }

    // The scenes a reader can go to next: each `option` names one by its address (3rd slot).
    on<InteractiveStoryBaseEvent> { e ->
        each(e.tags, StoryOptionTag::parse) { address(Relation.OPTION, it.address, StoryOptionTag.TAG_NAME) }
    }

    // The story (`A`, its root) and the scene the reader is on (`a`). `root`'s fallback to the
    // `d` tag (which holds the root's address) is not a link: no link comes from an event's own `d`.
    on<InteractiveStoryReadingStateEvent> { e ->
        each(e.tags, RootSceneTag::parse) { address(Relation.ROOT, it.toTag(), RootSceneTag.TAG_NAME) }
        each(e.tags, ATag::parse) { address(Relation.CURRENT_SCENE, it, ATag.TAG_NAME) }
    }
}

/**
 * The tags every Decentralized Lists item (9999, 39999) shares.
 *
 * - `z` names the list the item is on: a header's id, its coordinate, or the bare name of an
 *   undeclared list ([ParentListTag.classify]), so [Relation.PARENT_LIST] takes all three.
 * - `p` / `e` / `a` / `t` are the item itself. The `t` here is a list VALUE ("Switzerland"),
 *   case preserved, not a hashtag; an `a` may also be written as an `naddr1…`.
 *
 * [itemProps] qualifies every [Relation.ITEM] (a tagging's polarity).
 */
private fun LinkBuilder.experimentalListItemLinks(
    tags: TagArray,
    itemProps: ItemProps? = null,
) {
    each(tags, ParentListTag::parse) { parent ->
        when (parent) {
            is ParentList.EventId -> event(Relation.PARENT_LIST, parent.eventId, ParentListTag.TAG_NAME)
            is ParentList.Coordinate -> address(Relation.PARENT_LIST, parent.address, ParentListTag.TAG_NAME)
            is ParentList.Name -> tag(Relation.PARENT_LIST, ParentListTag.TAG_NAME, parent.name)
        }
    }
    each(tags, PTag::parse) { user(Relation.ITEM, it, PTag.TAG_NAME, itemProps) }
    each(tags, ETag::parse) { event(Relation.ITEM, it, ETag.TAG_NAME, itemProps) }
    each(tags, ATag::parse) { address(Relation.ITEM, it, ATag.TAG_NAME, itemProps) }
    // HashtagTag::parse keeps the case, which a list value needs; hashtags() would lowercase it.
    each(tags, HashtagTag::parse) { tag(Relation.ITEM, HashtagTag.TAG_NAME, it, HashtagTag.TAG_NAME, itemProps) }
}

/**
 * The trusted-list family's links after its members: an `a` or `p` that is not the member tag
 * ([memberTagName]) is discovery metadata, what the list is [Relation.ABOUT]; `observer` is the
 * point of view it was computed under and `source-tag` the tag definition it was computed from
 * (its author and slug are provenance, not links).
 */
private fun LinkBuilder.experimentalTrustedListLinks(
    list: TrustedListEvent,
    memberTagName: String,
) {
    if (memberTagName != ATag.TAG_NAME) each(list.tags, ATag::parse) { address(Relation.ABOUT, it, ATag.TAG_NAME) }
    if (memberTagName != PTag.TAG_NAME) each(list.tags, PTag::parse) { user(Relation.ABOUT, it, PTag.TAG_NAME) }
    each(list.tags, ObserverTag::parse) { user(Relation.OBSERVER, it, ObserverTag.TAG_NAME) }
    each(list.tags, SourceTag::parse) { event(Relation.SOURCE_TAG, it.eventId, SourceTag.TAG_NAME) }
}

/** A member's `score` (0..100) as the props of its `MEMBER` link; an unscored member has none. Main's Quartz has no `linkProps()` on the tag. */
private fun TrustedListMemberTag.memberProps() = MemberProps(score = score)

/** The spelling `publicationIdentifier` was read from: `T` when there is one, else `c`. */
private fun experimentalPublicationTagName(event: PublicationContentEvent) =
    if (event.tags.firstNotNullOfOrNull(ExperimentalPublicationIdTag::parse) != null) {
        ExperimentalPublicationIdTag.TAG_NAME
    } else {
        ExperimentalPublicationIdTag.ALT_TAG_NAME
    }
