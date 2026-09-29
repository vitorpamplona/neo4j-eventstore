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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RsvpProps
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip52Calendar.appt.day.CalendarDateSlotEvent
import com.vitorpamplona.quartz.nip52Calendar.appt.time.CalendarTimeSlotEvent
import com.vitorpamplona.quartz.nip52Calendar.calendar.CalendarCollectionEvent
import com.vitorpamplona.quartz.nip52Calendar.rsvp.CalendarRSVPEvent

/** Quartz's `nip52Calendar` classes. */
internal fun KindMappers.Builder.nip52Calendar() {
    on<CalendarDateSlotEvent> { e -> nip52CalendarSlotLinks(e.tags) }
    on<CalendarTimeSlotEvent> { e -> nip52CalendarSlotLinks(e.tags) }

    // NIP-52: a calendar is a set of `a` references to the date and time slots it includes.
    on<CalendarCollectionEvent> { e -> each(e.tags, ATag::parse) { address(Relation.MEMBER, it, ATag.TAG_NAME) } }

    // NIP-52: the calendar event responded to, by address and optionally by id, and its author. The RSVP status and free/busy flag ride on the calendar event links.
    on<CalendarRSVPEvent> { e ->
        val props = RsvpProps(e.statusValue(), e.freebusy()?.value)
        each(e.tags, ATag::parse) { address(Relation.CALENDAR_EVENT, it, ATag.TAG_NAME, props) }
        each(e.tags, ETag::parse) { event(Relation.CALENDAR_EVENT, it, ETag.TAG_NAME, props) }
        each(e.tags, PTag::parse) { user(Relation.CALENDAR_EVENT_AUTHOR, it, PTag.TAG_NAME) }
    }
}

/**
 * The links of a NIP-52 date or time slot (31922, 31923): its participants, with the optional
 * role NIP-52 puts in the `p` tag's fourth slot; the calendars (31924) it asks to be included in
 * (`a`); its topics, geohash and web references.
 */
private fun LinkBuilder.nip52CalendarSlotLinks(tags: TagArray) {
    each(tags, Nip52SlotParticipantTag::parse) { user(Relation.PARTICIPANT, it.pubKey, Nip52SlotParticipantTag.TAG_NAME, it.linkProps()) }
    each(tags, ATag::parse) { address(Relation.CALENDAR, it, ATag.TAG_NAME) }
    hashtags(tags)
    each(tags, GeoHashTag::parse) { tag(Relation.TAG, GeoHashTag.TAG_NAME, it) }
    each(tags, ReferenceTag::parse) { tag(Relation.TAG, ReferenceTag.TAG_NAME, it) }
}
