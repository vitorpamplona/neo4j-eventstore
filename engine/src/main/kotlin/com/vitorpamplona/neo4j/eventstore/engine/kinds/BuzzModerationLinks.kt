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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ActorProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ModerationProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.OwnerProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ResolutionProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchiveRequestEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchivedIdentitiesListEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchivedIdentityEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.UnarchiveRequestEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.UnarchivedIdentityEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.tags.Consent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.tags.ConsentTag
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.tags.ReplacedByTag
import com.vitorpamplona.quartz.buzz.moderation.ModerationBanEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationResolveReportEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationTimeoutEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationUntimeoutEvent
import com.vitorpamplona.quartz.buzz.moderation.ProductFeedbackEvent
import com.vitorpamplona.quartz.buzz.moderation.tags.ReportTag
import com.vitorpamplona.quartz.buzz.oaOwnerAttestation.OwnerAttestation
import com.vitorpamplona.quartz.buzz.oaOwnerAttestation.tags.AuthTag
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminAddMemberEvent
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminChangeRoleEvent
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminRemoveMemberEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag

/** Buzz's identity-archival (NIP-IA), moderation and relay-admin commands. */
internal fun KindMappers.Builder.buzzModeration() {
    // Identity archival
    // The `auth` owner is linked only when its attestation verifies for this event's author: an
    // unverified `auth` tag is a claim anyone can write about any key.
    on<ArchiveRequestEvent> { e ->
        user(Relation.ARCHIVED, e.target(), PTag.TAG_NAME, ModerationProps(e.reason()))
        user(Relation.REPLACED_BY, e.replacedBy(), ReplacedByTag.TAG_NAME)
        buzzVerifiedOwner(e.auth(), e.pubKey)
    }
    on<UnarchiveRequestEvent> { e ->
        user(Relation.UNARCHIVED, e.target(), PTag.TAG_NAME, ModerationProps(e.reason()))
        buzzVerifiedOwner(e.auth(), e.pubKey)
    }
    on<ArchivedIdentityEvent> { e ->
        user(Relation.ARCHIVED, e.target(), PTag.TAG_NAME, ModerationProps(e.reason()))
        e.consent()?.let { user(Relation.ACTOR, it.actorPubKey, ConsentTag.TAG_NAME, it.buzzActorProps()) }
        event(Relation.REQUEST, e.requestId(), ETag.TAG_NAME)
        user(Relation.REPLACED_BY, e.replacedBy(), ReplacedByTag.TAG_NAME)
    }
    on<UnarchivedIdentityEvent> { e ->
        user(Relation.UNARCHIVED, e.target(), PTag.TAG_NAME, ModerationProps(e.reason()))
        e.consent()?.let { user(Relation.ACTOR, it.actorPubKey, ConsentTag.TAG_NAME, it.buzzActorProps()) }
        event(Relation.REQUEST, e.requestId(), ETag.TAG_NAME)
    }
    on<ArchivedIdentitiesListEvent> { e ->
        each(e.tags, PTag::parse) { user(Relation.ARCHIVED, it, PTag.TAG_NAME) }
    }

    // Moderation
    on<ModerationBanEvent> { e ->
        user(Relation.BANNED, e.target(), PTag.TAG_NAME, ModerationProps(e.reason(), e.expiresAt()))
    }
    on<ModerationTimeoutEvent> { e ->
        user(Relation.TIMED_OUT, e.target(), PTag.TAG_NAME, ModerationProps(e.reason(), e.expiresAt()))
    }
    on<ModerationUntimeoutEvent> { e -> user(Relation.TIMEOUT_CLEARED, e.target(), PTag.TAG_NAME) }
    // The report rides in Buzz's own `report` tag, not an `e`.
    on<ModerationResolveReportEvent> { e ->
        event(Relation.RESOLVED, e.report(), ReportTag.TAG_NAME, ResolutionProps(e.status(), e.action(), e.reason()))
    }
    free<ProductFeedbackEvent>()

    // Relay admin
    on<RelayAdminAddMemberEvent> { e ->
        user(Relation.ADDED_USER, e.target(), PTag.TAG_NAME, RoleProps(listOfNotNull(e.role())))
    }
    on<RelayAdminChangeRoleEvent> { e ->
        user(Relation.ROLE_CHANGED, e.target(), PTag.TAG_NAME, RoleProps(listOfNotNull(e.role())))
    }
    on<RelayAdminRemoveMemberEvent> { e -> user(Relation.REMOVED_USER, e.target(), PTag.TAG_NAME) }
}

/**
 * The owner a NIP-OA `auth` tag names, linked only when the attestation verifies for
 * [agentPubKey] (the event's author). An unverified `auth` tag is a claim anyone can write about
 * any key, and OWNER is exactly the statement a reader would trust. Props: the attestation's
 * `conditions`, when it has any (empty means unconditional).
 */
private fun LinkBuilder.buzzVerifiedOwner(
    attestation: OwnerAttestation?,
    agentPubKey: HexKey,
) {
    if (attestation == null || !attestation.verify(agentPubKey)) return
    user(Relation.OWNER, attestation.ownerPubKey, AuthTag.TAG_NAME, OwnerProps(attestation.conditions.ifEmpty { null }))
}

/** The props of the `ACTOR` link to a consent's actor: which consent path authorized the mutation. */
private fun Consent.buzzActorProps() = ActorProps(path)
