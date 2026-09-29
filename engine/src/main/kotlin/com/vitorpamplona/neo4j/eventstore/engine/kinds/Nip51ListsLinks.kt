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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.LinkProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.MuteProps
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.release.tags.AppIdTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.nip51Lists.PinListEvent
import com.vitorpamplona.quartz.nip51Lists.appCurationSet.AppCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.articleCurationSet.ArticleCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.BookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.OldBookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.AddressBookmark
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.BookmarkIdTag
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark
import com.vitorpamplona.quartz.nip51Lists.bookmarkSet.BookmarkSetEvent
import com.vitorpamplona.quartz.nip51Lists.favoriteAlgoFeedsList.FavoriteAlgoFeedsListEvent
import com.vitorpamplona.quartz.nip51Lists.favoriteFollowSetsList.FavoriteFollowSetsListEvent
import com.vitorpamplona.quartz.nip51Lists.followSet.FollowSetEvent
import com.vitorpamplona.quartz.nip51Lists.geohashList.GeohashListEvent
import com.vitorpamplona.quartz.nip51Lists.gitAuthorList.GitAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.gitAuthorList.tags.GitAuthorTag
import com.vitorpamplona.quartz.nip51Lists.gitRepositoryList.GitRepositoryListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiAuthorList.GoodWikiAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiRelayList.GoodWikiRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.interestList.InterestListEvent
import com.vitorpamplona.quartz.nip51Lists.interestSet.InterestSetEvent
import com.vitorpamplona.quartz.nip51Lists.kindMuteSet.KindMuteSetEvent
import com.vitorpamplona.quartz.nip51Lists.mediaFollowList.MediaFollowListEvent
import com.vitorpamplona.quartz.nip51Lists.mediaStarterPack.MediaStarterPackEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.MuteListEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.EventTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.MuteTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.UserTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.WordTag
import com.vitorpamplona.quartz.nip51Lists.pictureCurationSet.PictureCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BlockedRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BroadcastRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.FavoriteRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.IndexerRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.ProxyRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.TrustedRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relaySets.RelaySetEvent
import com.vitorpamplona.quartz.nip51Lists.releaseArtifactSet.ReleaseArtifactSetEvent
import com.vitorpamplona.quartz.nip51Lists.simpleGroupList.GroupTag
import com.vitorpamplona.quartz.nip51Lists.simpleGroupList.SimpleGroupListEvent
import com.vitorpamplona.quartz.nip51Lists.starterPack.StarterPackEvent
import com.vitorpamplona.quartz.nip51Lists.videoCurationSet.VideoCurationSetEvent
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag as TopicTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.HashtagTag as MutedHashtagTag

// Only public tags are read: private (NIP-44 encrypted) entries are invisible to anyone but the
// owner, so they never become links.

/** Quartz's `nip51Lists` classes. */
internal fun KindMappers.Builder.nip51Lists() {
    // NIP-51: the notes pinned to the profile.
    on<PinListEvent> { e -> each(e.tags, EventBookmark::parse) { event(Relation.PIN, it.eventId, EventBookmark.TAG_NAME) } }

    // NIP-51: the curated software applications (kind 32267 `a` tags) are `CURATED`.
    on<AppCurationSetEvent> { e ->
        each(e.tags, AddressBookmark::parse) { address(Relation.CURATED, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51: the curated articles (`a`) and notes (`e`), `e` or `a`, are `CURATED`.
    on<ArticleCurationSetEvent> { e -> nip51EventsAndAddresses(Relation.CURATED, e.tags) }

    // NIP-51: every public `e`/`a` is a `BOOKMARK`. An author hint beside an `e` is not a link.
    on<BookmarkListEvent> { e -> nip51EventsAndAddresses(Relation.BOOKMARK, e.tags) }

    // The deprecated kind 30001 was every list at once, told apart by its `d`: `pin` became the
    // pin list (10001), `communities` the communities list (10004), anything else (`bookmark`)
    // the bookmarks (10003). Its `e`/`a` items mean what they mean in the list that replaced it.
    on<OldBookmarkListEvent> { e ->
        when (e.dTag()) {
            Nip51OldBookmarkDTags.PIN_D_TAG -> nip51EventsAndAddresses(Relation.PIN, e.tags)
            Nip51OldBookmarkDTags.COMMUNITIES_D_TAG -> nip51EventsAndAddresses(Relation.SUBSCRIBED, e.tags)
            else -> nip51EventsAndAddresses(Relation.BOOKMARK, e.tags)
        }
    }

    // NIP-51: every public `e`/`a` is a `BOOKMARK`.
    on<BookmarkSetEvent> { e -> nip51EventsAndAddresses(Relation.BOOKMARK, e.tags) }

    // The feed DVMs (kind 31990 `a` tags) the user marked as favorites.
    on<FavoriteAlgoFeedsListEvent> { e ->
        each(e.tags, AddressBookmark::parse) { address(Relation.FAVORITE, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51 kind 10021: the follow sets (kind 30000 `a` tags) the user favorited; other `a` kinds are skipped, as `publicFavoriteFollowSets` does.
    on<FavoriteFollowSetsListEvent> { e ->
        e.publicFavoriteFollowSets().forEach { address(Relation.FAVORITE, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51: a follow set's `p`s are its `MEMBER`s. The deprecated `d=mute` form is a mute list
    // (NIP-51: "use instead kind 10000"), which is why `publicMembers` parses mute entries: there
    // its `p`/`e`/`t`/`word` entries are `MUTE`s.
    on<FollowSetEvent> { e ->
        if (e.dTag() == FollowSetEvent.BLOCK_LIST_D_TAG) {
            nip51Mutes(e.tags)
        } else {
            each(e.tags, UserTag::parse) { user(Relation.MEMBER, it.pubKey, UserTag.TAG_NAME) }
        }
    }

    // The followed locations: every public `g` geohash is `SUBSCRIBED`.
    on<GeohashListEvent> { e -> each(e.tags, GeoHashTag::parse) { tag(Relation.SUBSCRIBED, GeoHashTag.TAG_NAME, it) } }

    // NIP-51: a follow list of code authors, so its `p`s are `SUBSCRIBED` (`FOLLOW` is kind 3 only).
    on<GitAuthorListEvent> { e -> each(e.tags, GitAuthorTag::parse) { user(Relation.SUBSCRIBED, it.pubKey, GitAuthorTag.TAG_NAME) } }

    // NIP-51: the followed NIP-34 repositories (kind 30617 `a` tags) are `SUBSCRIBED`.
    on<GitRepositoryListEvent> { e ->
        each(e.tags, AddressBookmark::parse) { address(Relation.SUBSCRIBED, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51: the user's "recommended wiki authors" are `RECOMMENDED`.
    on<GoodWikiAuthorListEvent> { e -> each(e.tags, UserTag::parse) { user(Relation.RECOMMENDED, it.pubKey, UserTag.TAG_NAME) } }

    free<GoodWikiRelayListEvent>()

    // NIP-51: the followed hashtags (`t`, the same lowercased node `HASHTAG` uses) and interest
    // sets (kind 30015 `a` tags; other `a` kinds are skipped, as `publicInterestSets` does) are
    // `SUBSCRIBED`.
    on<InterestListEvent> { e ->
        each(e.tags, TopicTag::parse) { tag(Relation.SUBSCRIBED, TopicTag.TAG_NAME, it.lowercase()) }
        e.publicInterestSets().forEach { address(Relation.SUBSCRIBED, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51: the hashtags that make up the interest (`t`, the same lowercased node `HASHTAG` uses) are its `MEMBER`s.
    on<InterestSetEvent> { e -> each(e.tags, TopicTag::parse) { tag(Relation.MEMBER, TopicTag.TAG_NAME, it.lowercase()) } }

    // NIP-51: the `p`s muted for one kind, which the `d` names; it rides as `muted_kind`, since a
    // mute of one kind is not a full mute.
    on<KindMuteSetEvent> { e ->
        val props = MuteProps(e.nip51MutedKind())
        each(e.tags, UserTag::parse) { user(Relation.MUTE, it.pubKey, UserTag.TAG_NAME, props) }
    }

    // NIP-51: a follow-like list, so its `p`s are `SUBSCRIBED` (`FOLLOW` is kind 3 only).
    on<MediaFollowListEvent> { e -> each(e.tags, UserTag::parse) { user(Relation.SUBSCRIBED, it.pubKey, UserTag.TAG_NAME) } }

    // NIP-51: the people in the pack are its `MEMBER`s.
    on<MediaStarterPackEvent> { e -> each(e.tags, UserTag::parse) { user(Relation.MEMBER, it.pubKey, UserTag.TAG_NAME) } }

    // NIP-51: every public entry (`p`, `e`, `t`, `word`) is a `MUTE`.
    on<MuteListEvent> { e -> nip51Mutes(e.tags) }

    // NIP-51: the curated pictures, `e` or `a`, are `CURATED`.
    on<PictureCurationSetEvent> { e -> nip51EventsAndAddresses(Relation.CURATED, e.tags) }

    free<BlockedRelayListEvent>()
    free<BroadcastRelayListEvent>()

    // NIP-51: the relay sets (kind 30002 `a` tags) among the favorite relays. The relay URLs themselves are not link targets.
    on<FavoriteRelayListEvent> { e -> e.publicRelaySets().forEach { address(Relation.FAVORITE, it.address, AddressBookmark.TAG_NAME) } }

    free<IndexerRelayListEvent>()
    free<ProxyRelayListEvent>()
    free<TrustedRelayListEvent>()
    free<RelaySetEvent>()

    // The release's artifacts (`e`: NIP-51 file metadata, NIP-82 kind 3063 assets) are
    // `CURATED`; the `a` is the software application (`APP`) they release and the NIP-82 `i` its
    // app id.
    on<ReleaseArtifactSetEvent> { e ->
        each(e.tags, AppIdTag::parse) { tag(Relation.TAG, AppIdTag.TAG_NAME, it) }
        each(e.tags, EventBookmark::parse) { event(Relation.CURATED, it.eventId, EventBookmark.TAG_NAME) }
        each(e.tags, AddressBookmark::parse) { address(Relation.APP, it.address, AddressBookmark.TAG_NAME) }
    }

    // NIP-51: the NIP-29 groups the user is in are `SUBSCRIBED`. A group is its id, the `h` value
    // its messages carry, so that is the target; the host relay is not part of it.
    on<SimpleGroupListEvent> { e ->
        // The group is its NIP-29 `h` id: one node whichever relay hosts it.
        each(e.tags, GroupTag::parse) { tag(Relation.SUBSCRIBED, GroupIdTag.TAG_NAME, it.groupId, GroupTag.TAG_NAME) }
    }

    // NIP-51: the people in the pack are its `MEMBER`s; `t` are its topics.
    on<StarterPackEvent> { e ->
        each(e.tags, UserTag::parse) { user(Relation.MEMBER, it.pubKey, UserTag.TAG_NAME) }
        hashtags(e.tags)
    }

    // NIP-51: the curated videos, `e` or `a`, are `CURATED`.
    on<VideoCurationSetEvent> { e -> nip51EventsAndAddresses(Relation.CURATED, e.tags) }
}

/** The `e` and `a` items ([BookmarkIdTag]) of a bookmark-like list, in tag order, as [relation]. */
private fun <P : LinkProps> LinkBuilder.nip51EventsAndAddresses(
    relation: Relation<P>,
    tags: TagArray,
) = each(tags, BookmarkIdTag::parse) {
    when (it) {
        is EventBookmark -> event(relation, it.eventId, EventBookmark.TAG_NAME)
        is AddressBookmark -> address(relation, it.address, AddressBookmark.TAG_NAME)
    }
}

/**
 * A mute list's entries ([MuteTag]), each a `MUTE`: people (`p`), threads (`e`), hashtags (`t`,
 * the same lowercased node `HASHTAG` uses) and words (`word`, lowercase as NIP-51 writes them).
 */
private fun LinkBuilder.nip51Mutes(tags: TagArray) =
    each(tags, MuteTag::parse) {
        when (it) {
            is UserTag -> user(Relation.MUTE, it.pubKey, UserTag.TAG_NAME)
            is EventTag -> event(Relation.MUTE, it.eventId, EventTag.TAG_NAME)
            is MutedHashtagTag -> tag(Relation.MUTE, MutedHashtagTag.TAG_NAME, it.hashtag.lowercase())
            is WordTag -> tag(Relation.MUTE, WordTag.TAG_NAME, it.word.lowercase())
        }
    }
