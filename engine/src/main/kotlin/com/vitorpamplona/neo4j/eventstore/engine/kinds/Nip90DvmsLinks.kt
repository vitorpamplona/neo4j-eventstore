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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.Link
import com.vitorpamplona.neo4j.eventstore.engine.vocab.LinkBuilder
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryRequest.DvmContentDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryRequest.tags.ParamTag
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryResponse.DvmContentDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.dvmHeartbeat.DvmHeartbeatEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.status.DvmStatusEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.tags.InputTag
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryRequest.DvmUserDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryResponse.DvmUserDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationResponseEvent

/**
 * Quartz's `nip90Dvms` classes. The job kinds share two shapes, one for every request
 * (5000-5999) and one for every result (6000-6999) and feedback (7000), so each class's mapper
 * is one of these calls.
 */
internal fun KindMappers.Builder.nip90Dvms() {
    // Requests
    on<DvmContentDiscoveryRequestEvent> { e -> nip90RequestLinks(e.tags, forUserParam = true) }
    on<DvmContentSearchRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmEventCountRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmEventPowDelegationRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmEventPublishScheduleRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmEventTimestampingRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmImageGenerationRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmImageToVideoRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmMalwareScanRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmOpReturnRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmPeopleSearchRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmSummarizationRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmTextExtractionRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmTextGenerationRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmTextToSpeechRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmTranslationRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmUserDiscoveryRequestEvent> { e -> nip90RequestLinks(e.tags, forUserParam = true) }
    on<DvmVideoConversionRequestEvent> { e -> nip90RequestLinks(e.tags) }
    on<DvmVideoTranslationRequestEvent> { e -> nip90RequestLinks(e.tags) }

    // Results
    on<DvmContentDiscoveryResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmContentSearchResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmEventCountResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmEventPowDelegationResponseEvent> { e -> nip90ResultLinks(e.tags) }
    // NIP-90 5905's output: the content is the id of the event the DVM published.
    on<DvmEventPublishScheduleResponseEvent> { e ->
        nip90ResultLinks(e.tags)
        event(Relation.RESULT, e.publishedEventId(), Link.VIA_CONTENT)
    }
    // NIP-90 5900's output: the content is the id of the kind 1040 proof, which itself links TIMESTAMPED.
    on<DvmEventTimestampingResponseEvent> { e ->
        nip90ResultLinks(e.tags)
        event(Relation.RESULT, e.otsEventId(), Link.VIA_CONTENT)
    }
    on<DvmImageGenerationResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmImageToVideoResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmMalwareScanResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmOpReturnResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmPeopleSearchResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmSummarizationResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmTextExtractionResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmTextGenerationResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmTextToSpeechResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmTranslationResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmUserDiscoveryResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmVideoConversionResponseEvent> { e -> nip90ResultLinks(e.tags) }
    on<DvmVideoTranslationResponseEvent> { e -> nip90ResultLinks(e.tags) }

    // NIP-90 job feedback: the request it reports on and its customer. Status and amount are values, not links.
    on<DvmStatusEvent> { e -> nip90ResultLinks(e.tags, withInputs = false) }
    free<DvmHeartbeatEvent>()
}

/**
 * The NIP-90 `["i", <data>, <input-type>, <relay>, <marker>]` inputs ([InputTag]). An `event`
 * input is the event the job works on; a `job` input is "the output of a previous job with the
 * specified event ID" (job chaining), so it points at that job's request; a `url` is a Tag
 * target. `text` and `prompt` inputs are free text, not references.
 */
private fun LinkBuilder.nip90Inputs(tags: TagArray) =
    each(tags, InputTag::parse) {
        when (it.type) {
            Nip90InputType.EVENT -> event(Relation.INPUT, it.value, InputTag.TAG_NAME)
            Nip90InputType.JOB -> event(Relation.INPUT_JOB, it.value, InputTag.TAG_NAME)
            Nip90InputType.URL -> value(Relation.INPUT, ValueType.URL, it.value, InputTag.TAG_NAME)
        }
    }

/**
 * A NIP-90 job request: its `i` inputs and, in `p`, the "Service Providers the customer is
 * interested in" ([Relation.SERVICE_PROVIDER]). With an `encrypted` tag the `i` and `param`
 * tags move into the NIP-04 content, so only `p` is left to link.
 * [forUserParam]: the discovery kinds (5300, 5301) name the user to compute for in
 * `["param", "user", <pubkey>]` ([Relation.FOR_USER]); their `p` stays the DVM, as Quartz and
 * Amethyst write it.
 */
private fun LinkBuilder.nip90RequestLinks(
    tags: TagArray,
    forUserParam: Boolean = false,
) {
    nip90Inputs(tags)
    each(tags, PTag::parse) { user(Relation.SERVICE_PROVIDER, it, PTag.TAG_NAME) }
    if (forUserParam) {
        each(tags, ParamTag::parse) { if (it.key == Nip90ParamKey.USER) user(Relation.FOR_USER, it.value, ParamTag.TAG_NAME) }
    }
}

/**
 * A NIP-90 job result or feedback: `e` is the job request it answers ([Relation.REQUEST]) and
 * `p` that request's author, the customer ([Relation.REQUEST_AUTHOR]). A result also copies the
 * request's `i` inputs ([withInputs]). The `request` tag re-embeds the request as JSON: the same
 * target as `e`, so not a second link.
 */
private fun LinkBuilder.nip90ResultLinks(
    tags: TagArray,
    withInputs: Boolean = true,
) {
    each(tags, ETag::parse) { event(Relation.REQUEST, it, ETag.TAG_NAME) }
    if (withInputs) nip90Inputs(tags)
    each(tags, PTag::parse) { user(Relation.REQUEST_AUTHOR, it, PTag.TAG_NAME) }
}
