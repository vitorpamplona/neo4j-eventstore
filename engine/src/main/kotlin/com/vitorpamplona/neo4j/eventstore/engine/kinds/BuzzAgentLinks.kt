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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.AuditProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.FrameProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.StatusProps
import com.vitorpamplona.quartz.buzz.aeEngrams.EngramEvent
import com.vitorpamplona.quartz.buzz.agentProfiles.AgentProfileEvent
import com.vitorpamplona.quartz.buzz.amTurnMetrics.AgentTurnMetricEvent
import com.vitorpamplona.quartz.buzz.amTurnMetrics.tags.AgentTag
import com.vitorpamplona.quartz.buzz.aoObserver.ObserverFrameEvent
import com.vitorpamplona.quartz.buzz.apPersonas.PersonaEvent
import com.vitorpamplona.quartz.buzz.audit.AuditAction
import com.vitorpamplona.quartz.buzz.audit.AuditEntryEvent
import com.vitorpamplona.quartz.buzz.audit.tags.ObjectTag
import com.vitorpamplona.quartz.buzz.huddles.HuddleEndedEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleGuidelinesEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleParticipantJoinedEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleParticipantLeftEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleReactionEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleStartedEvent
import com.vitorpamplona.quartz.buzz.jobs.JobAcceptedEvent
import com.vitorpamplona.quartz.buzz.jobs.JobCancelEvent
import com.vitorpamplona.quartz.buzz.jobs.JobErrorEvent
import com.vitorpamplona.quartz.buzz.jobs.JobProgressEvent
import com.vitorpamplona.quartz.buzz.jobs.JobRequestEvent
import com.vitorpamplona.quartz.buzz.jobs.JobResultEvent
import com.vitorpamplona.quartz.buzz.managedAgents.ManagedAgentEvent
import com.vitorpamplona.quartz.buzz.notifications.MemberAddedNotificationEvent
import com.vitorpamplona.quartz.buzz.notifications.MemberRemovedNotificationEvent
import com.vitorpamplona.quartz.buzz.pairing.PairingEvent
import com.vitorpamplona.quartz.buzz.workflow.ApprovalDenyEvent
import com.vitorpamplona.quartz.buzz.workflow.ApprovalGrantEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalDeniedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalGrantedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalRequestedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowCancelledEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowCompletedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowDefEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowFailedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepCompletedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepFailedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepStartedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowTriggerEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowTriggeredEvent
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag

/** Buzz's agent, telemetry, audit, job, workflow, huddle and membership-notification kinds. */
internal fun KindMappers.Builder.buzzAgents() {
    // Agents and their telemetry
    free<AgentProfileEvent>()
    free<PersonaEvent>()
    free<ManagedAgentEvent>()
    // The `d` is a blinded HMAC that only looks like an id: it is never linked.
    on<EngramEvent> { e -> user(Relation.OWNER, e.ownerPubKey(), PTag.TAG_NAME) }
    on<AgentTurnMetricEvent> { e ->
        user(Relation.OWNER, e.ownerPubKey(), PTag.TAG_NAME)
        user(Relation.AGENT, e.agentPubKey(), AgentTag.TAG_NAME)
    }
    on<ObserverFrameEvent> { e ->
        val frame = FrameProps(e.frame())
        user(Relation.RECIPIENT, e.recipientPubKey(), PTag.TAG_NAME, frame)
        user(Relation.AGENT, e.agentPubKey(), AgentTag.TAG_NAME, frame)
    }
    // The `p` is the peer's ephemeral pairing key, not a long-lived identity.
    on<PairingEvent> { e -> user(Relation.RECIPIENT, e.recipientPubKey(), PTag.TAG_NAME) }

    // The `object` is an event only when the action acts on one; otherwise it is a channel UUID
    // or a media hash, and a 64-hex hash must not become an event link.
    on<AuditEntryEvent> { e ->
        user(Relation.ACTOR, e.actor(), PTag.TAG_NAME)
        val action = e.action()
        val props = action?.code?.let { AuditProps(it) }
        when (action) {
            AuditAction.EVENT_CREATED, AuditAction.EVENT_DELETED -> event(Relation.AUDITED, e.objectId(), ObjectTag.TAG_NAME, props)
            else -> tag(Relation.AUDITED, ObjectTag.TAG_NAME, e.objectId(), props = props)
        }
    }

    // Jobs
    on<JobRequestEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.AGENT, e.target(), PTag.TAG_NAME)
    }
    on<JobAcceptedEvent> { e ->
        event(Relation.REQUEST, e.jobRequest(), ETag.TAG_NAME)
        buzzChannels(e.tags)
        user(Relation.REQUEST_AUTHOR, e.requester(), PTag.TAG_NAME)
    }
    on<JobProgressEvent> { e ->
        event(Relation.REQUEST, e.jobRequest(), ETag.TAG_NAME, StatusProps(e.status()))
        buzzChannels(e.tags)
    }
    on<JobResultEvent> { e ->
        event(Relation.REQUEST, e.jobRequest(), ETag.TAG_NAME, StatusProps(e.status()))
        buzzChannels(e.tags)
        user(Relation.REQUEST_AUTHOR, e.requester(), PTag.TAG_NAME)
    }
    on<JobErrorEvent> { e ->
        event(Relation.REQUEST, e.jobRequest(), ETag.TAG_NAME, StatusProps(e.status()))
        buzzChannels(e.tags)
        user(Relation.REQUEST_AUTHOR, e.requester(), PTag.TAG_NAME)
    }
    on<JobCancelEvent> { e ->
        event(Relation.REQUEST, e.jobRequest(), ETag.TAG_NAME)
        buzzChannels(e.tags)
    }

    // Workflows
    on<WorkflowDefEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowTriggerEvent> { e -> address(Relation.TRIGGERED, e.buzzWorkflowAddress(), DTag.TAG_NAME) }
    on<WorkflowTriggeredEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowStepStartedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowStepCompletedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowStepFailedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowCompletedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowFailedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowCancelledEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowApprovalRequestedEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.APPROVER, e.approver(), PTag.TAG_NAME)
    }
    on<WorkflowApprovalGrantedEvent> { e -> buzzChannels(e.tags) }
    on<WorkflowApprovalDeniedEvent> { e -> buzzChannels(e.tags) }
    // The grant and the denial act on an approval token hash in `d`, which is not a node.
    free<ApprovalGrantEvent>()
    free<ApprovalDenyEvent>()

    // Huddles
    on<HuddleStartedEvent> { e -> buzzChannels(e.tags) }
    on<HuddleParticipantJoinedEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.PARTICIPANT, e.participant(), PTag.TAG_NAME)
    }
    on<HuddleParticipantLeftEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.PARTICIPANT, e.participant(), PTag.TAG_NAME)
    }
    on<HuddleEndedEvent> { e ->
        buzzChannels(e.tags)
        user(Relation.PARTICIPANT, e.participant(), PTag.TAG_NAME)
    }
    on<HuddleGuidelinesEvent> { e -> buzzChannels(e.tags) }
    // The `h` is the ephemeral huddle channel, not the timeline channel it runs in.
    on<HuddleReactionEvent> { e -> buzzChannels(e.tags) }

    // Membership notifications: the actor rides in the content JSON, which links do not parse.
    on<MemberAddedNotificationEvent> { e ->
        user(Relation.ADDED_USER, e.target(), PTag.TAG_NAME)
        buzzChannels(e.tags)
    }
    on<MemberRemovedNotificationEvent> { e ->
        user(Relation.REMOVED_USER, e.target(), PTag.TAG_NAME)
        buzzChannels(e.tags)
    }
}

/**
 * The [WorkflowDefEvent] this trigger runs. The `d` on this REGULAR kind names the workflow, not
 * this event: it is the definition's `d`, under this author, since only the workflow's owner may
 * trigger it. (Main's Quartz has no `WorkflowTriggerEvent.workflowAddress()`.)
 */
private fun WorkflowTriggerEvent.buzzWorkflowAddress(): Address? = workflowId()?.let { Address(WorkflowDefEvent.KIND, pubKey, it) }
