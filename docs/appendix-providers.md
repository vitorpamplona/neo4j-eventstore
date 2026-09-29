# Appendix — Quartz hint providers, catalogued

Source: Quartz `quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/` at amethyst `main`
(2026-09-29). **Re-run this catalogue on every Quartz pin bump.** Each row is one golden test in
`:engine` `derive/` (spec §5, plan P2). A row that changes upstream turns its test red. Paths are
relative to the package root above.

The interfaces (`nip01Core/hints/HintProviders.kt`) return **ids only**. They carry no tag name
and no role:

```kotlin
interface EventHintProvider   { fun eventHints(): List<EventIdHint>; fun linkedEventIds(): List<HexKey> }
interface AddressHintProvider { fun addressHints(): List<AddressHint>; fun linkedAddressIds(): List<String> }
interface PubKeyHintProvider  { fun pubKeyHints(): List<PubKeyHint>;  fun linkedPubKeys(): List<HexKey> }
```

The spec (§5.2) recovers the tag (and, for NIP-10, the marker) by matching each linked id back
to the event's tags. It recovers roles from the kind-specific helpers (§5.3).

## Upstream gaps the store must shield itself from

These should also be filed or fixed in Quartz (plan Phase 0):

1. **`nsec` leak.** `nip19Bech32/ListEntityExt.pubKeys()` maps `NSec` to `.hex`, which is the
   *private key*. Every class that uses `citedNIP19()` therefore returns a pasted `nsec1…` as a
   "linked pubkey".
2. **`QTag.parseAddressId` / `parseAddressAsHint` reject every address.** They return null when
   the value contains `:`, and every address does. "Q (address)" below therefore produces
   nothing today. `QTag.parse` works.
3. **`ChannelCreateEvent.linkedEventIds()` returns its own id**, a self-loop.
4. **`ZapReceiptEvent` omits the zap sender**, i.e. the embedded request's author in
   `description`.
5. **Weak validation.** `ETag` / `PTag` / `MarkedETag` / `ContactTag` / `UserTag` parsers check
   only the tag name and a length of 64. `ATag.parseAddressId` returns any non-empty value.
6. **No store in Quartz consumes `linked*()`.** Callers are in `commons/`, `amethyst/` and `cli/`
   only, so nothing upstream exercises these as an indexing contract yet.

## The table

The legend for the "Ifaces" column is: **Ev** = `EventHintProvider`, **Addr** =
`AddressHintProvider`, **Pub** = `PubKeyHintProvider`.

Links are:
- **Q**: `q` tags;
- **NIP-19**: `nostr:` URIs in `content`, via `citedNIP19()`;
- plain `e` / `p` / `a`: `ETag.parseId` / `PTag.parseKey` / `ATag.parseAddressId` unless another
  parser is named.

The two base classes are `nip17Dm/base/BaseDMGroupEvent` (Pub: `p`) and
`nip34Git/status/GitStatusEvent` (Pub `p`, Ev `e` via `MarkedETag`, Addr `a`).

| Class | Kind | File | Ifaces | linkedEventIds | linkedAddressIds | linkedPubKeys |
|---|---|---|---|---|---|---|
| ContactListEvent | 3 | nip02FollowList/ContactListEvent.kt | Pub | – | – | `p` (ContactTag) |
| EncryptedDmEvent | 4 | nip04Dm/messages/EncryptedDmEvent.kt | Pub | – | – | p |
| DeletionRequestEvent | 5 | nip09Deletions/DeletionRequestEvent.kt | Ev,Addr | e | a | – |
| TextNoteEvent | 1 | nip10Notes/TextNoteEvent.kt | Ev,Addr,Pub | e (MarkedETag, all markers) + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| RepostEvent | 6 | nip18Reposts/RepostEvent.kt | Ev,Pub,Addr | e | a | p |
| ReactionEvent | 7 | nip25Reactions/ReactionEvent.kt | Ev,Pub,Addr | e | a | p |
| BadgeAwardEvent | 8 | nip58Badges/award/BadgeAwardEvent.kt | Ev,Addr,Pub | e | a | p |
| ChatEvent | 9 | nipC7Chats/ChatEvent.kt | Ev,Pub | Q + NIP-19 (no `e`) | – | p + NIP-19 |
| ChatMessageEvent | 14 | nip17Dm/messages/ChatMessageEvent.kt | Ev + Pub (inherited) | e | – | p |
| ChatMessageEncryptedFileHeaderEvent | 15 | nip17Dm/files/… | Pub (inherited) | – | – | p |
| GenericRepostEvent | 16 | nip18Reposts/GenericRepostEvent.kt | Ev,Pub,Addr | e | a | p |
| PublicMessageEvent | 24 | nipA4PublicMessages/PublicMessageEvent.kt | Pub,Ev,Addr | NIP-19 only | NIP-19 only | `p` (ReceiverTag) + NIP-19 |
| ChannelCreateEvent | 40 | nip28PublicChat/admin/ChannelCreateEvent.kt | Ev,Addr | **own id (self-loop)** | a | – |
| ChannelMetadataEvent | 41 | nip28PublicChat/admin/ChannelMetadataEvent.kt | Ev | `channelId()` (root e) | – | – |
| ChannelMessageEvent | 42 | nip28PublicChat/message/ChannelMessageEvent.kt | Ev,Addr,Pub | e (MarkedETag) + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| ChannelHideMessageEvent | 43 | nip28PublicChat/admin/ChannelHideMessageEvent.kt | Ev | e | – | – |
| ChannelMuteUserEvent | 44 | nip28PublicChat/admin/ChannelMuteUserEvent.kt | Pub | – | – | p |
| WikiMergeRequestEvent | 818 | nip54Wiki/WikiMergeRequestEvent.kt | Ev,Addr,Pub | e | a | p |
| WikiMergeAcceptanceEvent | 819 | nip54Wiki/WikiMergeAcceptanceEvent.kt | Ev,Pub | e | – | p |
| PollResponseEvent | 1018 | nip88Polls/response/PollResponseEvent.kt | Ev,Pub | `e` (PollTag) | – | p |
| BidEvent | 1021 | nip15Marketplace/bid/BidEvent.kt | Ev,Pub | e | – | p |
| BidConfirmationEvent | 1022 | nip15Marketplace/bidConfirmation/… | Ev,Pub | e | – | p |
| OtsEvent | 1040 | nip03Timestamp/OtsEvent.kt | Ev | `e` (TargetEventTag) | – | – |
| CommentEvent | 1111 | nip22Comments/CommentEvent.kt | Ev,Pub,Addr | `E` root + `e` reply + Q + NIP-19 | `A` root + `a` reply + Q + NIP-19 | `P` root + `p` reply + NIP-19 |
| WorkoutRecordEvent | 1301 | experimental/fitness/workout/WorkoutRecordEvent.kt | Addr | – | `exercise` + `template` tags | – |
| LiveActivitiesChatMessageEvent | 1311 | nip53LiveActivities/chat/… | Ev,Pub,Addr | e + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| LiveActivitiesRaidEvent | 1312 | nip53LiveActivities/raid/… | Addr | – | a | – |
| LiveActivitiesClipEvent | 1313 | nip53LiveActivities/clip/… | Addr,Pub | – | a | p |
| RoadEventConfirmationEvent | 1316 | experimental/roadstr/confirmation/… | Ev | `e` (RoadReportTag) | – | – |
| GitPatchEvent | 1617 | nip34Git/patch/GitPatchEvent.kt | Pub,Ev,Addr | e (MarkedETag) | a | p |
| GitPullRequestEvent | 1618 | nip34Git/pr/GitPullRequestEvent.kt | Pub,Ev,Addr | e | a | p |
| GitPullRequestUpdateEvent | 1619 | nip34Git/pr/GitPullRequestUpdateEvent.kt | Pub,Ev,Addr | `E` only | a | p + `P` |
| GitIssueEvent | 1621 | nip34Git/issue/GitIssueEvent.kt | Pub,Ev,Addr | Q + NIP-19 (no `e`) | a + Q + NIP-19 | p + NIP-19 |
| GitReplyEvent | 1622 | nip34Git/reply/GitReplyEvent.kt | Pub,Ev,Addr | e + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| GitStatusOpen/Applied/Closed/DraftEvent | 1630–1633 | nip34Git/status/… | inherited | e (MarkedETag) | a | p |
| LabelEvent | 1985 | nip32Labeling/LabelEvent.kt | Ev,Pub,Addr | e | a | p |
| ReportEvent | 1984 | nip56Reports/ReportEvent.kt | Pub,Ev,Addr | `e` (ReportedEventTag) | `a` (ReportedAddressTag) | `p` (ReportedAuthorTag) |
| TorrentCommentEvent | 2004 | nip35Torrents/TorrentCommentEvent.kt | Ev,Pub,Addr | e (MarkedETag) + Q + NIP-19 | Q + NIP-19 (no `a`) | p + NIP-19 |
| CommunityPostApprovalEvent | 4550 | nip72ModCommunities/approval/… | Ev,Addr,Pub | `e` (ApprovedEventTag) | `a` (ApprovedAddressTag) + `a` (CommunityTag) | p |
| ZapPollEvent | 6969 | experimental/zapPolls/ZapPollEvent.kt | Ev,Addr,Pub | e (MarkedETag) + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| CashuSpendingHistoryEvent | 7376 | nip60Cashu/history/… | Ev,Pub | e (public tags only) | – | p |
| GeocacheFoundLogEvent | 7516 | nipCCGeocaching/foundLog/… | Addr | – | `a` (GeocacheTag) | – |
| OnchainZapEvent | 8333 | nipBCOnchainZaps/zap/OnchainZapEvent.kt | Ev,Addr,Pub | e | a | p |
| ZapGoalEvent | 9041 | nip75ZapGoals/ZapGoalEvent.kt | Ev,Addr,Pub | e | a | p |
| NutzapEvent | 9321 | nip61Nutzaps/nutzap/NutzapEvent.kt | Ev,Pub | e | – | p |
| PrivateZapEvent | 9733 | nip57Zaps/PrivateZapEvent.kt | Ev,Addr,Pub | e | a | p |
| ZapRequestEvent | 9734 | nip57Zaps/ZapRequestEvent.kt | Ev,Addr,Pub | e | a | p |
| ZapReceiptEvent | 9735 | nip57Zaps/ZapReceiptEvent.kt | Ev,Addr,Pub | e | a | p (recipient; **sender omitted**) |
| Bolt12ZapEvent | 9736 | nipB1Bolt12Zaps/zap/… | Ev,Addr,Pub | e | a | p |
| Bolt12ZapIntentEvent | 9737 | nipB1Bolt12Zaps/intent/… | Ev,Addr,Pub | e | a | p |
| HighlightEvent | 9802 | nip84Highlights/HighlightEvent.kt | Ev,Addr,Pub | e + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| ListItemEvent | 9999 | experimental/decentralizedLists/item/… | Ev,Addr,Pub | e, else `z` | a (validated), else `z` | p |
| MuteListEvent | 10000 | nip51Lists/muteList/MuteListEvent.kt | Pub | – | – | `p` (UserTag) |
| PinListEvent | 10001 | nip51Lists/PinListEvent.kt | Ev | `e` (EventBookmark) | – | – |
| BookmarkListEvent | 10003 | nip51Lists/bookmarkList/… | Ev,Addr | `e` (EventBookmark) | `a` (AddressBookmark) | – |
| CommunityListEvent | 10004 | nip72ModCommunities/follow/… | Addr | – | `a` (CommunityTag) | – |
| PublicChatListEvent | 10005 | nip28PublicChat/list/… | Ev | `e` (ChannelTag) | – | – |
| ProfileBadgesEvent | 10008 | nip58Badges/profile/… | Ev,Addr,Pub | e | a | p |
| GitAuthorListEvent | 10017 | nip51Lists/gitAuthorList/… | Pub | – | – | `p` (GitAuthorTag) |
| GitRepositoryListEvent | 10018 | nip51Lists/gitRepositoryList/… | Addr | – | `a` (AddressBookmark) | – |
| MediaFollowListEvent | 10020 | nip51Lists/mediaFollowList/… | Pub | – | – | `p` (UserTag) |
| EmojiListEvent | 10030 | nip30CustomEmoji/selection/… | Addr | – | a | – |
| AuthoredPodcastsEvent | 10064 | nipF4Podcasts/authored/… | Pub | – | – | `p` (UserTag) |
| GoodWikiAuthorListEvent | 10101 | nip51Lists/goodWikiAuthorList/… | Pub | – | – | `p` (UserTag) |
| MeetingRoomPresenceEvent | 10312 | nip53LiveActivities/presence/… | Addr | – | `a` (MeetingSpaceTag) | – |
| WakeUpEvent | 23903 | experimental/notifications/wake/… | Ev | e | – | – |
| FollowSetEvent | 30000 | nip51Lists/followSet/… | Pub | – | – | `p` (UserTag) |
| OldBookmarkListEvent | 30001 | nip51Lists/bookmarkList/… | Ev,Addr | `e` (EventBookmark) | `a` (AddressBookmark) | – |
| BookmarkSetEvent | 30003 | nip51Lists/bookmarkSet/… | Ev,Addr | same | same | – |
| ArticleCurationSetEvent | 30004 | nip51Lists/articleCurationSet/… | Ev,Addr | same | same | – |
| VideoCurationSetEvent | 30005 | nip51Lists/videoCurationSet/… | Ev,Addr | same | same | – |
| PictureCurationSetEvent | 30006 | nip51Lists/pictureCurationSet/… | Ev | `e` (EventBookmark) | – | – |
| KindMuteSetEvent | 30007 | nip51Lists/kindMuteSet/… | Pub | – | – | `p` (UserTag) |
| AcceptedBadgeSetEvent | 30008 | nip58Badges/accepted/… | Ev,Addr,Pub | e | a | p |
| LongFormContentEvent | 30023 | nip23LongContent/LongFormContentEvent.kt | Ev,Pub,Addr | Q + NIP-19 (no `e`) | Q + NIP-19 (**no `a`**) | p + NIP-19 |
| PublicationIndexEvent | 30040 | experimental/publications/… | Addr,Pub | – | a | p |
| BookshelfDirectoryEvent | 30045 | experimental/library/… | Addr | – | a | – |
| ReleaseArtifactSetEvent | 30063 | nip51Lists/releaseArtifactSet/… | Ev,Addr | `e` (EventBookmark) | `a` (AddressBookmark) | – |
| AppCurationSetEvent | 30267 | nip51Lists/appCurationSet/… | Addr | – | `a` (AddressBookmark) | – |
| LiveActivitiesEvent | 30311 | nip53LiveActivities/streaming/… | Ev,Pub | `pinned` (PinnedEventTag) | – | `p` (ParticipantTag) |
| MeetingSpaceEvent | 30312 | nip53LiveActivities/meetingSpaces/… | Pub | – | – | `p` (ParticipantTag) |
| MeetingRoomEvent | 30313 | nip53LiveActivities/meetingSpaces/… | Ev,Addr,Pub | `pinned` | `a` (MeetingSpaceTag) | `p` (ParticipantTag) |
| UserTrustedListEvent | 30392 | experimental/trustedLists/users/… | Pub,Addr | – | a | `p` (PubKeyMemberTag) |
| EventTrustedListEvent | 30393 | experimental/trustedLists/events/… | Ev,Addr,Pub | `e` (EventMemberTag) | a | p |
| AddressableTrustedListEvent | 30394 | experimental/trustedLists/addressables/… | Addr,Pub | – | `a` (AddressMemberTag) | p |
| ExternalIdTrustedListEvent | 30395 | experimental/trustedLists/externalIds/… | Addr,Pub | – | a | p |
| ClassifiedsEvent | 30402 | nip99Classifieds/ClassifiedsEvent.kt | Pub,Ev,Addr | e | a | p |
| NipTextEvent | 30817 | experimental/nipsOnNostr/NipTextEvent.kt | Ev,Addr | Q + NIP-19 (no `e`) | a + Q + NIP-19 | – |
| WikiArticleEvent | 30818 | nip54Wiki/WikiArticleEvent.kt | Ev,Addr,Pub | e (MarkedETag) + Q + NIP-19 | a + Q + NIP-19 | p + NIP-19 |
| WikiRedirectEvent | 30819 | nip54Wiki/WikiRedirectEvent.kt | Addr | – | a | – |
| AttestationEvent | 31871 | experimental/attestations/attestation/… | Ev,Addr | e | a | – |
| AttestationRequestEvent | 31872 | experimental/attestations/request/… | Ev,Addr,Pub | e | a | p |
| CalendarDateSlotEvent | 31922 | nip52Calendar/appt/day/… | Pub | – | – | p |
| CalendarTimeSlotEvent | 31923 | nip52Calendar/appt/time/… | Pub | – | – | p |
| CalendarCollectionEvent | 31924 | nip52Calendar/calendar/… | Addr | – | a | – |
| CalendarRSVPEvent | 31925 | nip52Calendar/rsvp/… | Pub,Addr,Ev | e | a | p |
| AppRecommendationEvent | 31989 | nip89AppHandlers/recommendation/… | Addr | – | `a` (RecommendationTag) | – |
| VideoCollaborationEvent | 34238 | experimental/videoCollaboration/… | Addr,Pub | – | a | p |
| EntityRatingEvent | 34259 | experimental/ratings/EntityRatingEvent.kt | Ev,Addr,Pub | e | a + `A` | p |
| CommunityDefinitionEvent | 34550 | nip72ModCommunities/definition/… | Ev,Addr,Pub | e + Q | a + Q | `p` (ModeratorTag) |
| CommunityRulesEvent | 34551 | nip72ModCommunities/rules/… | Addr | – | a | – |
| GeocacheCurationListEvent | 37517 | nipCCGeocaching/curation/… | Addr | – | a | – |
| StarterPackEvent | 39089 | nip51Lists/starterPack/… | Pub | – | – | `p` (UserTag) |
| MediaStarterPackEvent | 39092 | nip51Lists/mediaStarterPack/… | Pub | – | – | `p` (UserTag) |
| TextTrackEvent | 39307 | nip71Video/textTrack/… | Addr | – | a | – |
| AddressableListItemEvent | 39999 | experimental/decentralizedLists/item/… | Ev,Addr,Pub | e, else `z` | a (validated), else `z` | p |

## Kinds with references but no provider

These are covered by the generic fallback (spec §5.2 rule 5) or by a link rule (§5.3).

About 305 concrete Quartz kinds implement no provider. The ones that still carry references:

- **Threads:**
  - VoiceReplyEvent 1244 (NIP-22-style)
  - VoiceEvent 1222
  - ThreadEvent 11
- **Media:**
  - Video 21 / 22 / 34235 / 34236 (p/e/a)
  - PictureEvent 20 (p)
  - FileMetadataEvent 1063 (e)
  - FileStorageHeaderEvent 1065 (e)
- **Wrappers:** GiftWrapEvent 1059, EphemeralGiftWrapEvent 21059 (p).
- **NIP-90 DVMs:** 5000–6999 and 7000 (e/p/a).
- **NIP-29 groups:** 9000 / 9001 / 39002 (p), 9005 (e), 39001.
- **NIP-43 membership:** 8000 / 8001 (p).
- **Calls and chess:** calls 25050–25055 (p); chess 64 and 30064–30068 (e/p).
- **Status and edits:**
  - UserStatusEvent 30315 (e/p/a)
  - TextNoteModificationEvent 1010 (e)
  - ConcordChatEditEvent 3302 (e)
  - ProfileGalleryEntryEvent 1163 (e)
- **Addressable references:** 34139, 30296–30298 (A/a), 31990, 32267, 38000, 7517 (a); 10154 (p).
- **Payments and admin:** clink 21001–21003 (e/p), nests 4312 (a/p), WelcomeEvent 444 (e).
- **buzz/:** 9040–9043, 9030–9032, 8002 / 8003 / 9035 / 9036 / 13535 (e/p), 39005 (e).
- **NIP-85 assertions:**
  - 30382 / 30383 / 30384 put the subject in **`d`** (spec §5.2 rule 4);
  - 10040 names services in multi-letter `"<kind>:<type>"` tags (spec §5.3 link rule).

## Where roles come from

The Quartz helpers the `RoleTable` delegates to:

- **NIP-10:**
  - `nip10Notes/BaseThreadedEvent.kt`: `root()`, `reply()`, `markedRoot()`, `unmarkedRoot()`, `mentions()`
  - `nip10Notes/tags/MarkedETag.kt`: `MARKER {ROOT, REPLY, MENTION, FORK}`
  - `TextNoteEvent.isAFork()`
- **NIP-22:** `CommentEvent.rootEventIds()` / `replyEventIds()` / `rootAddressIds()` /
  `replyAddressIds()` / `rootAuthorKeys()` / `replyAuthorKeys()` / `rootKinds()` / `directKinds()`.
- **NIP-18:**
  - `BaseRepostEvent.boostedEventId()` / `boostedAddress()`, which take the last e/a;
  - `containedPost()`;
  - quotes: `nip18Reposts/quotes/` `taggedQuotes()`, `QTag.parse`.
- **NIP-25:** `ReactionEvent.originalPost()` / `originalAuthor()`. Quartz has no "last e"
  helper, so that rule is store-side.
- **NIP-57:** `ZapReceiptEventInterface.zappedPost()` / `zappedAuthor()` / `zappedRequestAuthor()`,
  and `ZapReceiptEvent.zapRequest`.
- **NIP-09, 56, 32:** `DeletionRequestEvent.deleteEventIds()` / `deleteAddressIds()`;
  `ReportEvent.reportedAuthorsWithOwnType()`; `LabelEvent.labeled*()`.
- **NIP-51, 72, 58:** the `UserTag` / `EventBookmark` / `AddressBookmark` tag classes, the NIP-72
  `CommunityTag` / `ApprovedEventTag` / `ModeratorTag`, and the NIP-58 award/profile classes.
- **NIP-85:** `UserAssertionEvent.aboutUser()`, `EventAssertionEvent.aboutEvent()`,
  `AddressableAssertionEvent.aboutAddress()`, `TrustProviderListEvent.serviceProviders()`.
- **Generic:** `nip01Core/tags/{events,people,aTag}/EventExt.kt`: `taggedEvents()`,
  `taggedUsers()`, `taggedAddresses()`.

Typed events come from `utils/EventFactory.create(id, pubKey, createdAt, kind, tags, content, sig)`
(roughly 400 kinds). `EventFactory.isKnownKind(kind)` defines the default `KindRegistry`.
