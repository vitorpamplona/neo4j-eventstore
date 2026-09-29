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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.CollaborationProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.StatusProps
import com.vitorpamplona.quartz.experimental.agora.FundraiserEvent
import com.vitorpamplona.quartz.experimental.attestations.attestation.AttestationEvent
import com.vitorpamplona.quartz.experimental.attestations.attestation.tags.RequestTag
import com.vitorpamplona.quartz.experimental.attestations.proficiency.AttestorProficiencyEvent
import com.vitorpamplona.quartz.experimental.attestations.recommendation.AttestorRecommendationEvent
import com.vitorpamplona.quartz.experimental.attestations.recommendation.tags.KindTag
import com.vitorpamplona.quartz.experimental.attestations.request.AttestationRequestEvent
import com.vitorpamplona.quartz.experimental.audio.header.AudioHeaderEvent
import com.vitorpamplona.quartz.experimental.audio.track.AudioTrackEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdDetectionEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdexEvent
import com.vitorpamplona.quartz.experimental.bitchat.geohash.GeohashChatEvent
import com.vitorpamplona.quartz.experimental.bitchat.geohash.GeohashPresenceEvent
import com.vitorpamplona.quartz.experimental.citations.CitationEvent
import com.vitorpamplona.quartz.experimental.citations.ExternalCitationEvent
import com.vitorpamplona.quartz.experimental.citations.tags.CitationTags
import com.vitorpamplona.quartz.experimental.clink.debits.DebitEvent
import com.vitorpamplona.quartz.experimental.clink.manage.ManageEvent
import com.vitorpamplona.quartz.experimental.clink.offers.OfferEvent
import com.vitorpamplona.quartz.experimental.edits.TextNoteModificationEvent
import com.vitorpamplona.quartz.experimental.ephemChat.chat.EphemeralChatEvent
import com.vitorpamplona.quartz.experimental.ephemChat.list.EphemeralChatListEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.ExerciseTemplateEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.WorkoutRecordEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.ExerciseSetTag
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.TemplateTag
import com.vitorpamplona.quartz.experimental.medical.FhirResourceEvent
import com.vitorpamplona.quartz.experimental.nests.admin.AdminCommandEvent
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.application.SoftwareApplicationEvent
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.asset.SoftwareAssetEvent
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.release.tags.AppIdTag
import com.vitorpamplona.quartz.experimental.nip95.data.FileStorageEvent
import com.vitorpamplona.quartz.experimental.nip95.header.FileStorageHeaderEvent
import com.vitorpamplona.quartz.experimental.nns.NNSEvent
import com.vitorpamplona.quartz.experimental.notifications.wake.WakeUpEvent
import com.vitorpamplona.quartz.experimental.profileGallery.ProfileGalleryEntryEvent
import com.vitorpamplona.quartz.experimental.ps1saves.Ps1SaveEvent
import com.vitorpamplona.quartz.experimental.roadstr.confirmation.RoadEventConfirmationEvent
import com.vitorpamplona.quartz.experimental.roadstr.confirmation.tags.RoadReportTag
import com.vitorpamplona.quartz.experimental.roadstr.report.RoadEventReportEvent
import com.vitorpamplona.quartz.experimental.roadstr.report.tags.RoadEventTypeTag
import com.vitorpamplona.quartz.experimental.videoCollaboration.VideoCollaborationEvent
import com.vitorpamplona.quartz.experimental.videoCollaboration.tags.StatusTag
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag as NipKindTag

/** Quartz's `experimental` classes: the lists and the note-like kinds have their own files. */
internal fun KindMappers.Builder.experimental() {
    on<FundraiserEvent> { e -> hashtags(e.tags) }

    // The attested assertion (an `e` or an `a`) and the kind-31872 request it answers.
    on<AttestationEvent> { e ->
        each(e.tags, ETag::parse) { event(Relation.ASSERTION, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parse) { address(Relation.ASSERTION, it, ATag.TAG_NAME) }
        each(e.tags, RequestTag::parse) { address(Relation.REQUEST, it.toTag(), RequestTag.TAG_NAME) }
    }

    // The kinds this attestor declares it can attest.
    on<AttestorProficiencyEvent> { e -> each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) } }

    // The recommended attestor is this event's `d` ([build] writes the pubkey there). Like a
    // NIP-85 assertion's subject, it is the thing the event is about, not the event's own
    // identity, so it is a link; the validator drops a `d` that is not a pubkey.
    on<AttestorRecommendationEvent> { e ->
        user(Relation.RECOMMENDED, e.dTag(), DTag.TAG_NAME)
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
    }

    // The assertion to attest (an `e` or an `a`) and the attestors asked to (`p`, see `attestorPubKeys`).
    on<AttestationRequestEvent> { e ->
        each(e.tags, ETag::parse) { event(Relation.ASSERTION, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parse) { address(Relation.ASSERTION, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(Relation.ATTESTOR, it, PTag.TAG_NAME) }
    }

    free<AudioHeaderEvent>()

    // Zapstr writes each participant's role (Host, Artist…) in the 4th slot of its `p`.
    on<AudioTrackEvent> { e ->
        each(e.tags, ExperimentalAudioParticipantTag::parse) {
            user(Relation.PARTICIPANT, it.tag, ExperimentalAudioParticipantTag.TAG_NAME, it.linkProps())
        }
    }

    // The species (`i`, a Wikidata URL) and where it was seen (`g`).
    on<BirdDetectionEvent> { e ->
        each(e.tags, ExperimentalSpeciesIdTag::parse) { tag(Relation.TAG, ExperimentalSpeciesIdTag.TAG_NAME, it) }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }

    // Every species on the life list, by its `i` (a Wikidata URL).
    on<BirdexEvent> { e -> each(e.tags, ExperimentalSpeciesIdTag::parse) { tag(Relation.TAG, ExperimentalSpeciesIdTag.TAG_NAME, it) } }

    // The channel cell (`g`) and the `t` tags (Bitchat writes `teleport` there).
    on<GeohashChatEvent> { e ->
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
        hashtags(e.tags)
    }
    on<GeohashPresenceEvent> { e -> each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) } }

    // The location the source was consulted from (`g`). The cited source itself is a value (a title, a DOI, a url).
    on<CitationEvent> { e -> tag(Relation.TAG, GeoHashTag.TAG_NAME, e.geohash()) }

    // The NIP-03 timestamp attesting when the page was seen, then the base's `g`.
    on<ExternalCitationEvent> { e ->
        event(Relation.OPEN_TIMESTAMP, e.openTimestamp(), CitationTags.OPEN_TIMESTAMP)
        tag(Relation.TAG, GeoHashTag.TAG_NAME, e.geohash())
    }

    // The counterparty the message is addressed to (`p`) and, on a response, the request it answers (`e`).
    on<OfferEvent> { e ->
        user(Relation.RECIPIENT, e.recipientPubKey(), PTag.TAG_NAME)
        event(Relation.REQUEST, e.requestId(), ETag.TAG_NAME)
    }
    on<DebitEvent> { e ->
        user(Relation.RECIPIENT, e.recipientPubKey(), PTag.TAG_NAME)
        event(Relation.REQUEST, e.requestId(), ETag.TAG_NAME)
    }
    on<ManageEvent> { e ->
        user(Relation.RECIPIENT, e.recipientPubKey(), PTag.TAG_NAME)
        event(Relation.REQUEST, e.requestId(), ETag.TAG_NAME)
    }

    // The edited note (the first `e`, as `editedNote` reads it) and its author, whom the `p` notifies.
    on<TextNoteModificationEvent> { e ->
        event(Relation.EDITED, e.editedNote(), ETag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.EDITED_AUTHOR, it, PTag.TAG_NAME) }
    }

    free<EphemeralChatEvent>()
    free<EphemeralChatListEvent>()
    free<ExerciseTemplateEvent>()

    // POWR / NIP-101e exercise and workout templates, by coordinate. A RUNSTR `exercise` is a
    // plain verb, not a coordinate, and the parser skips it.
    on<WorkoutRecordEvent> { e ->
        each(e.tags, ExerciseSetTag::parseAddressId) { address(Relation.EXERCISE, it, ExerciseSetTag.TAG_NAME) }
        each(e.tags, TemplateTag::parseAddressId) { address(Relation.TEMPLATE, it, TemplateTag.TAG_NAME) }
        hashtags(e.tags)
    }

    free<FhirResourceEvent>()

    // The room (its kind-30312 `a`, which the presence and chat kinds tag as their root) and
    // the target, whose relation is the verb: a kick or a mute, the two queried apart (rule 4).
    // A command with an unknown verb names nobody. Room and target are the first WELL-FORMED
    // `a` / `p` (main's `room()` / `targetPubkey()` take the first of the name, parsed or not).
    on<AdminCommandEvent> { e ->
        address(Relation.ROOT, e.tags.firstNotNullOfOrNull(ATag::parse), ATag.TAG_NAME)
        when (e.action()) {
            AdminCommandEvent.Action.KICK -> user(Relation.KICKED, e.tags.firstNotNullOfOrNull(PTag::parse), PTag.TAG_NAME)
            AdminCommandEvent.Action.MUTE -> user(Relation.CHANNEL_MUTED, e.tags.firstNotNullOfOrNull(PTag::parse), PTag.TAG_NAME)
            null -> Unit
        }
    }

    // zapstore apps `a`-tag their latest kind-30063 release.
    on<SoftwareApplicationEvent> { e ->
        each(e.tags, ATag::parse) { address(Relation.RELEASE, it, ATag.TAG_NAME) }
        hashtags(e.tags)
    }

    // The application this asset belongs to, by its `i`: the 32267's `d` identifier, not a NIP-73 id.
    on<SoftwareAssetEvent> { e -> tag(Relation.TAG, AppIdTag.TAG_NAME, e.appId()) }

    free<FileStorageEvent>()

    // The kind-1064 events holding the bytes. The `e` tag's author slot is a hint, not a statement.
    on<FileStorageHeaderEvent> { e -> each(e.tags, ETag::parse) { event(Relation.FILE_DATA, it, ETag.TAG_NAME) } }

    free<NNSEvent>()

    // The events this wake-up is about and their authors: the `p` tags name the AUTHORS of the
    // subject events, not a recipient (see `notifies`).
    on<WakeUpEvent> { e ->
        each(e.tags, ETag::parse) { event(Relation.ABOUT, it, ETag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(Relation.ABOUT_AUTHOR, it, PTag.TAG_NAME) }
        each(e.tags, NipKindTag::parse) { tag(Relation.TAG, NipKindTag.TAG_NAME, it.toString()) }
    }

    // The event the picture was taken from.
    on<ProfileGalleryEntryEvent> { e -> event(Relation.SOURCE, e.fromEvent(), ETag.TAG_NAME) }

    free<Ps1SaveEvent>()

    // The confirmed (or denied) report, with the answer as `status`, and the location cells.
    on<RoadEventConfirmationEvent> { e ->
        val status = e.status()?.let { StatusProps(it.code) }
        each(e.tags, RoadReportTag::parse) { event(Relation.CONFIRMED, it, RoadReportTag.TAG_NAME, status) }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }

    // The road-event type code (`t`: police, accident…) and the location cells (`g`, at several precisions).
    on<RoadEventReportEvent> { e ->
        // The raw code, unknown types included, lowercased like any hashtag.
        each(e.tags, RoadEventTypeTag::parseCode) { tag(Relation.HASHTAG, RoadEventTypeTag.TAG_NAME, it.lowercase()) }
        each(e.tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    }

    // The video credited and its author, with the answer: `status` (absent means accepted, see
    // `isAccepted`) and the credited `role`. The divine-mobile `d` repeats the video's coordinate,
    // but no link comes from an event's own `d`.
    on<VideoCollaborationEvent> { e ->
        val props = CollaborationProps(roles = listOfNotNull(e.role()), status = e.status() ?: StatusTag.ACCEPTED)
        each(e.tags, ATag::parse) { address(Relation.COLLABORATED, it, ATag.TAG_NAME, props) }
        each(e.tags, PTag::parse) { user(Relation.COLLABORATED_AUTHOR, it, PTag.TAG_NAME, props) }
    }

    experimentalLists()
    experimentalNotes()
}
