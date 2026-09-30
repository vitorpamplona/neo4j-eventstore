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
package com.vitorpamplona.neo4j.eventstore.engine.vocab

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ActorProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.AuctionProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.AuditProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.BidProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ChessResultProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.CollaborationProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.CreditProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.FoundProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.FrameProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ItemProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.LabelProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.LinkProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.MemberProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ModerationProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.MuteProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.NoProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.OrderProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.OwnerProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ParticipantProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.PlatformProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.PollResponseProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.PositionProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RatingProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ReleaseProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ReportProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ResolutionProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RsvpProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ServiceProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.StatusProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.SubjectProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ViewProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.VoteProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.WotProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ZapSplitProps

/**
 * What a link's target IS to the event that states it: its `ROOT`, its `PARENT`, the
 * `ZAP_RECIPIENT`. The relation's name is the relationship type in the graph, so these names are
 * API (`docs/schema.md`); the vocabulary, its rules and every class's use of it are in
 * `docs/vocabulary.md` and its appendix.
 *
 * Names are Nostr's own words for the slot (a NIP's marker or noun), else the past participle of
 * the NIP's action (`REACTED`, `DELETED`); lists name their entries as the list does (`FOLLOW`,
 * `BOOKMARK`). One relation per role across kinds: the SOURCE event's kind says which kind of
 * parent a `PARENT` is. UPPER_SNAKE, the Cypher convention for relationship types.
 *
 * [P] is the props type the relation's links carry ([NoProps] for none): the builder only accepts
 * that type for it, and it is the relation's schema for a consumer.
 *
 * Every relation the projection writes is a constant here, so renaming one is a visible, breaking
 * change: a major schema version.
 */
class Relation<P : LinkProps>(
    val name: String,
    vararg targets: Target,
) {
    /** What a link of this relation may point at ([Target]); [LinkBuilder] refuses anything else. */
    val targets: Set<Target> = targets.toSet()

    override fun equals(other: Any?) = other is Relation<*> && other.name == name

    override fun hashCode() = name.hashCode()

    override fun toString() = name

    companion object {
        // Authorship and identity
        val AUTHOR = Relation<NoProps>("AUTHOR", NodeTarget.USER)
        val ADDRESS = Relation<NoProps>("ADDRESS", NodeTarget.ADDRESS)

        // Conversation
        val ROOT = Relation<NoProps>("ROOT", NodeTarget.EVENT, NodeTarget.ADDRESS, *ValueType.EXTERNAL_CONTENT)
        val PARENT = Relation<NoProps>("PARENT", NodeTarget.EVENT, NodeTarget.ADDRESS, ValueType.GROUP, *ValueType.EXTERNAL_CONTENT)
        val ROOT_AUTHOR = Relation<NoProps>("ROOT_AUTHOR", NodeTarget.USER)
        val PARENT_AUTHOR = Relation<NoProps>("PARENT_AUTHOR", NodeTarget.USER)
        val MENTION = Relation<NoProps>("MENTION", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, *ValueType.EXTERNAL_CONTENT)
        val QUOTE = Relation<NoProps>("QUOTE", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val FORK = Relation<NoProps>("FORK", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val EDITED = Relation<NoProps>("EDITED", NodeTarget.EVENT)
        val EDITED_AUTHOR = Relation<NoProps>("EDITED_AUTHOR", NodeTarget.USER)
        val RECIPIENT = Relation<FrameProps>("RECIPIENT", NodeTarget.USER)
        val COMMUNITY = Relation<NoProps>("COMMUNITY", NodeTarget.ADDRESS)
        val REPOSITORY = Relation<NoProps>("REPOSITORY", NodeTarget.ADDRESS, ValueType.GIT_COMMIT)
        val REPOSITORY_OWNER = Relation<NoProps>("REPOSITORY_OWNER", NodeTarget.USER)

        // Reactions, reposts, zaps
        val REACTED = Relation<NoProps>("REACTED", NodeTarget.EVENT, NodeTarget.ADDRESS, *ValueType.EXTERNAL_CONTENT)
        val REACTED_AUTHOR = Relation<NoProps>("REACTED_AUTHOR", NodeTarget.USER)
        val REPOSTED = Relation<NoProps>("REPOSTED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val REPOSTED_AUTHOR = Relation<NoProps>("REPOSTED_AUTHOR", NodeTarget.USER)
        val ZAPPED = Relation<ZapProps>("ZAPPED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val ZAP_RECIPIENT = Relation<ZapProps>("ZAP_RECIPIENT", NodeTarget.USER)
        val ZAP_SENDER = Relation<NoProps>("ZAP_SENDER", NodeTarget.USER)
        val HIGHLIGHTED = Relation<NoProps>("HIGHLIGHTED", NodeTarget.EVENT, NodeTarget.ADDRESS, *ValueType.EXTERNAL_CONTENT)
        val HIGHLIGHTED_AUTHOR = Relation<RoleProps>("HIGHLIGHTED_AUTHOR", NodeTarget.USER)

        /**
         * A `nostr:` reference inside text the event QUOTES rather than writes (a highlight's
         * excerpt): the quoted author named it, so it is not the event author's `MENTION`.
         */
        val CITED = Relation<NoProps>("CITED", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER)
        val RATED =
            Relation<RatingProps>("RATED", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, ValueType.EXTERNAL, ValueType.HASHTAG)
        val RATED_AUTHOR = Relation<RatingProps>("RATED_AUTHOR", NodeTarget.USER)

        // Moderation
        val DELETED = Relation<NoProps>("DELETED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val DELETED_AUTHOR = Relation<NoProps>("DELETED_AUTHOR", NodeTarget.USER)
        val REPORTED_USER = Relation<ReportProps>("REPORTED_USER", NodeTarget.USER)
        val REPORTED = Relation<ReportProps>("REPORTED", NodeTarget.EVENT, NodeTarget.ADDRESS, ValueType.SHA256)
        val REPORTED_AUTHOR = Relation<ReportProps>("REPORTED_AUTHOR", NodeTarget.USER)
        val LABELED =
            Relation<LabelProps>("LABELED", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, ValueType.HASHTAG, ValueType.URL)
        val MUTE = Relation<MuteProps>("MUTE", NodeTarget.EVENT, NodeTarget.USER, ValueType.HASHTAG, ValueType.WORD)
        val HIDDEN = Relation<NoProps>("HIDDEN", NodeTarget.EVENT, ValueType.GROUP)
        val CHANNEL_MUTED = Relation<NoProps>("CHANNEL_MUTED", NodeTarget.USER)
        val APPROVED = Relation<NoProps>("APPROVED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val APPROVED_AUTHOR = Relation<NoProps>("APPROVED_AUTHOR", NodeTarget.USER)
        val MODERATOR = Relation<NoProps>("MODERATOR", NodeTarget.USER)

        // Social graph and lists
        val FOLLOW = Relation<NoProps>("FOLLOW", NodeTarget.USER)
        val SUBSCRIBED =
            Relation<NoProps>(
                "SUBSCRIBED",
                NodeTarget.EVENT,
                NodeTarget.ADDRESS,
                NodeTarget.USER,
                ValueType.GEOHASH,
                ValueType.GROUP,
                ValueType.HASHTAG,
            )
        val FAVORITE = Relation<NoProps>("FAVORITE", NodeTarget.ADDRESS, NodeTarget.USER)
        val MEMBER = Relation<MemberProps>("MEMBER", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, *ValueType.EXTERNAL_CONTENT)
        val RECOMMENDED = Relation<PlatformProps>("RECOMMENDED", NodeTarget.ADDRESS, NodeTarget.USER)
        val BOOKMARK = Relation<NoProps>("BOOKMARK", NodeTarget.EVENT, NodeTarget.ADDRESS, ValueType.URL)
        val CURATED = Relation<OrderProps>("CURATED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val PIN = Relation<OrderProps>("PIN", NodeTarget.EVENT, NodeTarget.ADDRESS)

        // Badges (NIP-58)
        val AWARDED = Relation<NoProps>("AWARDED", NodeTarget.USER)
        val BADGE_DEFINITION = Relation<NoProps>("BADGE_DEFINITION", NodeTarget.ADDRESS)
        val BADGE_AWARD = Relation<NoProps>("BADGE_AWARD", NodeTarget.EVENT)
        val BADGE_SET = Relation<NoProps>("BADGE_SET", NodeTarget.ADDRESS)

        // Trust (NIP-85)
        val SUBJECT = Relation<SubjectProps>("SUBJECT", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, *ValueType.EXTERNAL_CONTENT)
        val SERVICE_PROVIDER = Relation<ServiceProps>("SERVICE_PROVIDER", NodeTarget.USER)

        // Events, calendars, live activities, markets
        val PARTICIPANT = Relation<ParticipantProps>("PARTICIPANT", NodeTarget.USER)
        val CALENDAR_EVENT = Relation<RsvpProps>("CALENDAR_EVENT", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val CALENDAR_EVENT_AUTHOR = Relation<NoProps>("CALENDAR_EVENT_AUTHOR", NodeTarget.USER)
        val CALENDAR = Relation<NoProps>("CALENDAR", NodeTarget.ADDRESS)
        val RAIDED = Relation<NoProps>("RAIDED", NodeTarget.ADDRESS)
        val CLIPPED = Relation<NoProps>("CLIPPED", NodeTarget.ADDRESS)
        val CLIPPED_AUTHOR = Relation<NoProps>("CLIPPED_AUTHOR", NodeTarget.USER)
        val POLL = Relation<PollResponseProps>("POLL", NodeTarget.EVENT)
        val POLL_AUTHOR = Relation<NoProps>("POLL_AUTHOR", NodeTarget.USER)
        val AUCTION = Relation<AuctionProps>("AUCTION", NodeTarget.EVENT)
        val AUCTION_AUTHOR = Relation<NoProps>("AUCTION_AUTHOR", NodeTarget.USER)
        val BID = Relation<BidProps>("BID", NodeTarget.EVENT)
        val BID_AUTHOR = Relation<NoProps>("BID_AUTHOR", NodeTarget.USER)
        val TIMESTAMPED = Relation<NoProps>("TIMESTAMPED", NodeTarget.EVENT)
        val REDIRECT = Relation<NoProps>("REDIRECT", NodeTarget.ADDRESS)

        // Topics, places and other values: what the value IS to the event (rule 7), never a
        // generic "tag". A kind value takes the role of the target it qualifies (`X_KIND`, as
        // `X_AUTHOR` names the author of the `X` target).
        val HASHTAG = Relation<NoProps>("HASHTAG", ValueType.HASHTAG)
        val LOCATION = Relation<NoProps>("LOCATION", ValueType.GEOHASH)
        val REFERENCE = Relation<NoProps>("REFERENCE", ValueType.URL)
        val LANGUAGE = Relation<NoProps>("LANGUAGE", ValueType.LANGUAGE)
        val LABEL = Relation<NoProps>("LABEL", ValueType.LABEL)
        val LABEL_NAMESPACE = Relation<NoProps>("LABEL_NAMESPACE", ValueType.LABEL_NAMESPACE)
        val IDENTITY = Relation<NoProps>("IDENTITY", ValueType.IDENTITY)
        val SPECIES = Relation<NoProps>("SPECIES", *ValueType.EXTERNAL_CONTENT)
        val SCHEMA = Relation<NoProps>("SCHEMA", ValueType.SCHEMA_HASH)
        val SCHEMA_NAMESPACE = Relation<NoProps>("SCHEMA_NAMESPACE", ValueType.SCHEMA_NAMESPACE)
        val TRANSACTION = Relation<NoProps>("TRANSACTION", ValueType.BITCOIN_TX)
        val TORRENT = Relation<NoProps>("TORRENT", ValueType.TORRENT)
        val KEY_PACKAGE_REF = Relation<NoProps>("KEY_PACKAGE_REF", ValueType.KEY_PACKAGE_REF)
        val ROOT_KIND = Relation<NoProps>("ROOT_KIND", ValueType.KIND)
        val PARENT_KIND = Relation<NoProps>("PARENT_KIND", ValueType.KIND)
        val REACTED_KIND = Relation<NoProps>("REACTED_KIND", ValueType.KIND)
        val REPOSTED_KIND = Relation<NoProps>("REPOSTED_KIND", ValueType.KIND)
        val DELETED_KIND = Relation<NoProps>("DELETED_KIND", ValueType.KIND)
        val ZAPPED_KIND = Relation<NoProps>("ZAPPED_KIND", ValueType.KIND)
        val TIMESTAMPED_KIND = Relation<NoProps>("TIMESTAMPED_KIND", ValueType.KIND)
        val APPROVED_KIND = Relation<NoProps>("APPROVED_KIND", ValueType.KIND)
        val SUBJECT_KIND = Relation<NoProps>("SUBJECT_KIND", ValueType.KIND)
        val RATED_KIND = Relation<NoProps>("RATED_KIND", ValueType.KIND)
        val RECOMMENDED_KIND = Relation<NoProps>("RECOMMENDED_KIND", ValueType.KIND)
        val ABOUT_KIND = Relation<NoProps>("ABOUT_KIND", ValueType.KIND)
        val SUPPORTED_KIND = Relation<NoProps>("SUPPORTED_KIND", ValueType.KIND)
        val ALLOWED_KIND = Relation<NoProps>("ALLOWED_KIND", ValueType.KIND)
        val DEFINED_KIND = Relation<NoProps>("DEFINED_KIND", ValueType.KIND)
        val DRAFT_KIND = Relation<NoProps>("DRAFT_KIND", ValueType.KIND)

        // Every kind: tags any event may carry
        val CLIENT = Relation<NoProps>("CLIENT", NodeTarget.ADDRESS)
        val ZAP_SPLIT = Relation<ZapSplitProps>("ZAP_SPLIT", NodeTarget.USER)
        val EMOJI_SET = Relation<NoProps>("EMOJI_SET", NodeTarget.ADDRESS)

        // Added by the per-class review (see the appendix for each one's kinds and reason)
        val ABOUT = Relation<NoProps>("ABOUT", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, *ValueType.EXTERNAL_CONTENT)
        val ABOUT_AUTHOR = Relation<NoProps>("ABOUT_AUTHOR", NodeTarget.USER)
        val ACCEPTED = Relation<NoProps>("ACCEPTED", NodeTarget.EVENT)
        val ACTOR = Relation<ActorProps>("ACTOR", NodeTarget.USER)
        val ADDED_USER = Relation<RoleProps>("ADDED_USER", NodeTarget.USER)
        val ADMIN = Relation<RoleProps>("ADMIN", NodeTarget.USER)
        val AGENT = Relation<FrameProps>("AGENT", NodeTarget.USER)
        val ALLOWED = Relation<RoleProps>("ALLOWED", NodeTarget.USER)
        val APP = Relation<NoProps>("APP", NodeTarget.ADDRESS, ValueType.APP)
        val APPLIED = Relation<NoProps>("APPLIED", NodeTarget.EVENT)
        val APPROVER = Relation<NoProps>("APPROVER", NodeTarget.USER)
        val ARCHIVED = Relation<ModerationProps>("ARCHIVED", NodeTarget.USER)
        val ASSERTION = Relation<NoProps>("ASSERTION", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val ATTESTOR = Relation<NoProps>("ATTESTOR", NodeTarget.USER)
        val AUDITED = Relation<AuditProps>("AUDITED", NodeTarget.EVENT, ValueType.OBJECT)
        val AUTHORED = Relation<NoProps>("AUTHORED", NodeTarget.USER)
        val BANNED = Relation<ModerationProps>("BANNED", NodeTarget.USER)
        val BASE_VERSION = Relation<NoProps>("BASE_VERSION", NodeTarget.EVENT)
        val CHILD = Relation<NoProps>("CHILD", ValueType.GROUP)
        val COLLABORATED = Relation<CollaborationProps>("COLLABORATED", NodeTarget.ADDRESS)
        val COLLABORATED_AUTHOR = Relation<CollaborationProps>("COLLABORATED_AUTHOR", NodeTarget.USER)
        val CONCEPT_GRAPH = Relation<NoProps>("CONCEPT_GRAPH", NodeTarget.ADDRESS)
        val CONFIRMED = Relation<StatusProps>("CONFIRMED", NodeTarget.EVENT)
        val COPIED = Relation<NoProps>("COPIED", NodeTarget.ADDRESS)
        val CREATED = Relation<NoProps>("CREATED", NodeTarget.EVENT)
        val CREDITED = Relation<CreditProps>("CREDITED", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER)
        val CURRENT_SCENE = Relation<NoProps>("CURRENT_SCENE", NodeTarget.ADDRESS)
        val DEFER = Relation<NoProps>("DEFER", NodeTarget.ADDRESS)
        val DENIED = Relation<NoProps>("DENIED", NodeTarget.USER)
        val DESTINATION = Relation<NoProps>("DESTINATION", NodeTarget.ADDRESS)
        val DESTINATION_AUTHOR = Relation<NoProps>("DESTINATION_AUTHOR", NodeTarget.USER)
        val DESTROYED = Relation<NoProps>("DESTROYED", NodeTarget.EVENT)
        val ELEMENT_OF = Relation<NoProps>("ELEMENT_OF", NodeTarget.ADDRESS)
        val EXERCISE = Relation<NoProps>("EXERCISE", NodeTarget.ADDRESS)
        val FILE_DATA = Relation<NoProps>("FILE_DATA", NodeTarget.EVENT)
        val FINDER = Relation<NoProps>("FINDER", NodeTarget.USER)
        val FOR_USER = Relation<NoProps>("FOR_USER", NodeTarget.USER)
        val FOUND = Relation<FoundProps>("FOUND", NodeTarget.ADDRESS)
        val FUNDED = Relation<NoProps>("FUNDED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val GOAL = Relation<NoProps>("GOAL", NodeTarget.EVENT)
        val GROUP = Relation<NoProps>("GROUP", ValueType.GROUP)
        val INHERIT_FROM = Relation<NoProps>("INHERIT_FROM", NodeTarget.ADDRESS)
        val INPUT = Relation<NoProps>("INPUT", NodeTarget.EVENT, ValueType.URL)
        val INPUT_JOB = Relation<NoProps>("INPUT_JOB", NodeTarget.EVENT)
        val ITEM = Relation<ItemProps>("ITEM", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER, ValueType.HASHTAG)
        val KEY_PACKAGE = Relation<NoProps>("KEY_PACKAGE", NodeTarget.EVENT)
        val KICKED = Relation<NoProps>("KICKED", NodeTarget.USER)
        val LINKED = Relation<NoProps>("LINKED", NodeTarget.EVENT, NodeTarget.ADDRESS, NodeTarget.USER)
        val MAINTAINER = Relation<NoProps>("MAINTAINER", NodeTarget.USER)
        val NOTIFICATION_SERVER = Relation<NoProps>("NOTIFICATION_SERVER", NodeTarget.USER)
        val OBSERVER = Relation<NoProps>("OBSERVER", NodeTarget.USER)
        val OPEN_TIMESTAMP = Relation<NoProps>("OPEN_TIMESTAMP", NodeTarget.EVENT)
        val OPPONENT = Relation<ChessResultProps>("OPPONENT", NodeTarget.USER)
        val OPTION = Relation<NoProps>("OPTION", NodeTarget.ADDRESS)
        val ORIGIN = Relation<NoProps>("ORIGIN", NodeTarget.ADDRESS)
        val OWNER = Relation<OwnerProps>("OWNER", NodeTarget.USER)
        val PARENT_LIST = Relation<NoProps>("PARENT_LIST", NodeTarget.EVENT, NodeTarget.ADDRESS, ValueType.LIST)
        val PODCAST_AUTHOR = Relation<RoleProps>("PODCAST_AUTHOR", NodeTarget.USER)
        val PUBLICATION = Relation<NoProps>("PUBLICATION", NodeTarget.ADDRESS)
        val REDEEMED = Relation<NoProps>("REDEEMED", NodeTarget.EVENT)
        val REDEEMED_AUTHOR = Relation<NoProps>("REDEEMED_AUTHOR", NodeTarget.USER)
        val RELEASE = Relation<NoProps>("RELEASE", NodeTarget.ADDRESS)
        val REMINDED = Relation<NoProps>("REMINDED", NodeTarget.EVENT)
        val REMOVED_USER = Relation<NoProps>("REMOVED_USER", NodeTarget.USER)
        val REPLACED_BY = Relation<NoProps>("REPLACED_BY", NodeTarget.USER)
        val REQUEST = Relation<StatusProps>("REQUEST", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val REQUEST_AUTHOR = Relation<NoProps>("REQUEST_AUTHOR", NodeTarget.USER)
        val RESOLVED = Relation<ResolutionProps>("RESOLVED", NodeTarget.EVENT)
        val RESULT = Relation<NoProps>("RESULT", NodeTarget.EVENT)
        val REVISED = Relation<NoProps>("REVISED", NodeTarget.EVENT)
        val ROLE_CHANGED = Relation<RoleProps>("ROLE_CHANGED", NodeTarget.USER)
        val SITE_MANIFEST = Relation<ReleaseProps>("SITE_MANIFEST", NodeTarget.ADDRESS)
        val SNAPSHOTTED = Relation<NoProps>("SNAPSHOTTED", NodeTarget.ADDRESS)
        val SOURCE = Relation<NoProps>("SOURCE", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val SOURCE_TAG = Relation<NoProps>("SOURCE_TAG", NodeTarget.EVENT)
        val SUBSET_OF = Relation<NoProps>("SUBSET_OF", NodeTarget.ADDRESS)
        val TAGGED = Relation<PositionProps>("TAGGED", NodeTarget.USER)
        val TEMPLATE = Relation<NoProps>("TEMPLATE", NodeTarget.ADDRESS)
        val TEXT_TRACK = Relation<NoProps>("TEXT_TRACK", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val TIMED_OUT = Relation<ModerationProps>("TIMED_OUT", NodeTarget.USER)
        val TIMEOUT_CLEARED = Relation<NoProps>("TIMEOUT_CLEARED", NodeTarget.USER)
        val TRIGGERED = Relation<NoProps>("TRIGGERED", NodeTarget.ADDRESS)
        val UNARCHIVED = Relation<ModerationProps>("UNARCHIVED", NodeTarget.USER)
        val VERIFIED = Relation<NoProps>("VERIFIED", NodeTarget.ADDRESS)
        val VERIFIER = Relation<NoProps>("VERIFIER", NodeTarget.USER)
        val VIEWED = Relation<ViewProps>("VIEWED", NodeTarget.EVENT, NodeTarget.ADDRESS)
        val VIDEO = Relation<NoProps>("VIDEO", NodeTarget.ADDRESS)
        val VIEWER = Relation<NoProps>("VIEWER", NodeTarget.USER)
        val VOTED = Relation<VoteProps>("VOTED", NodeTarget.EVENT)
        val WIKILINK = Relation<NoProps>("WIKILINK", NodeTarget.EVENT, ValueType.WIKI)
        val WIKILINK_AUTHOR = Relation<NoProps>("WIKILINK_AUTHOR", NodeTarget.USER)
        val WINNER = Relation<ChessResultProps>("WINNER", NodeTarget.USER)
        val WOT_ROOT = Relation<WotProps>("WOT_ROOT", NodeTarget.USER)

        /** Every relation the projection writes: the schema's relationship types. */
        val ALL: List<Relation<*>> =
            listOf(
                AUTHOR,
                ADDRESS,
                ROOT,
                PARENT,
                ROOT_AUTHOR,
                PARENT_AUTHOR,
                MENTION,
                QUOTE,
                FORK,
                EDITED,
                EDITED_AUTHOR,
                RECIPIENT,
                COMMUNITY,
                REPOSITORY,
                REPOSITORY_OWNER,
                REACTED,
                REACTED_AUTHOR,
                REPOSTED,
                REPOSTED_AUTHOR,
                ZAPPED,
                ZAP_RECIPIENT,
                ZAP_SENDER,
                HIGHLIGHTED,
                HIGHLIGHTED_AUTHOR,
                CITED,
                RATED,
                RATED_AUTHOR,
                DELETED,
                DELETED_AUTHOR,
                REPORTED_USER,
                REPORTED,
                REPORTED_AUTHOR,
                LABELED,
                MUTE,
                HIDDEN,
                CHANNEL_MUTED,
                APPROVED,
                APPROVED_AUTHOR,
                MODERATOR,
                FOLLOW,
                SUBSCRIBED,
                FAVORITE,
                MEMBER,
                RECOMMENDED,
                BOOKMARK,
                CURATED,
                PIN,
                AWARDED,
                BADGE_DEFINITION,
                BADGE_AWARD,
                BADGE_SET,
                SUBJECT,
                SERVICE_PROVIDER,
                PARTICIPANT,
                CALENDAR_EVENT,
                CALENDAR_EVENT_AUTHOR,
                CALENDAR,
                RAIDED,
                CLIPPED,
                CLIPPED_AUTHOR,
                POLL,
                POLL_AUTHOR,
                AUCTION,
                AUCTION_AUTHOR,
                BID,
                BID_AUTHOR,
                TIMESTAMPED,
                REDIRECT,
                HASHTAG,
                LOCATION,
                REFERENCE,
                LANGUAGE,
                LABEL,
                LABEL_NAMESPACE,
                IDENTITY,
                SPECIES,
                SCHEMA,
                SCHEMA_NAMESPACE,
                TRANSACTION,
                TORRENT,
                KEY_PACKAGE_REF,
                ROOT_KIND,
                PARENT_KIND,
                REACTED_KIND,
                REPOSTED_KIND,
                DELETED_KIND,
                ZAPPED_KIND,
                TIMESTAMPED_KIND,
                APPROVED_KIND,
                SUBJECT_KIND,
                RATED_KIND,
                RECOMMENDED_KIND,
                ABOUT_KIND,
                SUPPORTED_KIND,
                ALLOWED_KIND,
                DEFINED_KIND,
                DRAFT_KIND,
                CLIENT,
                ZAP_SPLIT,
                EMOJI_SET,
                ABOUT,
                ABOUT_AUTHOR,
                ACCEPTED,
                ACTOR,
                ADDED_USER,
                ADMIN,
                AGENT,
                ALLOWED,
                APP,
                APPLIED,
                APPROVER,
                ARCHIVED,
                ASSERTION,
                ATTESTOR,
                AUDITED,
                AUTHORED,
                BANNED,
                BASE_VERSION,
                CHILD,
                COLLABORATED,
                COLLABORATED_AUTHOR,
                CONCEPT_GRAPH,
                CONFIRMED,
                COPIED,
                CREATED,
                CREDITED,
                CURRENT_SCENE,
                DEFER,
                DENIED,
                DESTINATION,
                DESTINATION_AUTHOR,
                DESTROYED,
                ELEMENT_OF,
                EXERCISE,
                FILE_DATA,
                FINDER,
                FOR_USER,
                FOUND,
                FUNDED,
                GOAL,
                GROUP,
                INHERIT_FROM,
                INPUT,
                INPUT_JOB,
                ITEM,
                KEY_PACKAGE,
                KICKED,
                LINKED,
                MAINTAINER,
                NOTIFICATION_SERVER,
                OBSERVER,
                OPEN_TIMESTAMP,
                OPPONENT,
                OPTION,
                ORIGIN,
                OWNER,
                PARENT_LIST,
                PODCAST_AUTHOR,
                PUBLICATION,
                REDEEMED,
                REDEEMED_AUTHOR,
                RELEASE,
                REMINDED,
                REMOVED_USER,
                REPLACED_BY,
                REQUEST,
                REQUEST_AUTHOR,
                RESOLVED,
                RESULT,
                REVISED,
                ROLE_CHANGED,
                SITE_MANIFEST,
                SNAPSHOTTED,
                SOURCE,
                SOURCE_TAG,
                SUBSET_OF,
                TAGGED,
                TEMPLATE,
                TEXT_TRACK,
                TIMED_OUT,
                TIMEOUT_CLEARED,
                TRIGGERED,
                UNARCHIVED,
                VERIFIED,
                VERIFIER,
                VIEWED,
                VIDEO,
                VIEWER,
                VOTED,
                WIKILINK,
                WIKILINK_AUTHOR,
                WINNER,
                WOT_ROOT,
            )
    }
}
