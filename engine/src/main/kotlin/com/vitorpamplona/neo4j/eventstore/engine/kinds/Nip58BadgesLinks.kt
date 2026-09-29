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
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip58Badges.accepted.AcceptedBadgeSetEvent
import com.vitorpamplona.quartz.nip58Badges.award.BadgeAwardEvent
import com.vitorpamplona.quartz.nip58Badges.definition.BadgeDefinitionEvent
import com.vitorpamplona.quartz.nip58Badges.profile.ProfileBadgesEvent

/** Quartz's `nip58Badges` classes. */
internal fun KindMappers.Builder.nip58Badges() {
    // NIP-58: the set lists badges as consecutive `a`/`e` pairs (`acceptedBadges`): the badge
    // definition and the award that granted it. An `a` or `e` outside a pair is not a badge.
    on<AcceptedBadgeSetEvent> { e ->
        e.acceptedBadges().forEach { badge ->
            address(Relation.BADGE_DEFINITION, badge.badgeDefinition, ATag.TAG_NAME)
            event(Relation.BADGE_AWARD, badge.badgeAward, ETag.TAG_NAME)
        }
    }

    // NIP-58: the `a` is the badge definition being awarded and each `p` a pubkey the issuer
    // awards it to. NIP-58 defines no `e` on a badge award.
    on<BadgeAwardEvent> { e ->
        each(e.tags, ATag::parse) { address(Relation.BADGE_DEFINITION, it, ATag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(Relation.AWARDED, it, PTag.TAG_NAME) }
    }

    free<BadgeDefinitionEvent>()

    // NIP-58: the profile shows badges as consecutive `a`/`e` pairs (`acceptedBadges`): the badge
    // definition and the award that granted it. An `a` to a kind 30008 badge set is a
    // `BADGE_SET` the profile displays whole, never a badge definition.
    on<ProfileBadgesEvent> { e ->
        e.acceptedBadges().forEach { badge ->
            if (badge.badgeDefinition.kind == AcceptedBadgeSetEvent.KIND) return@forEach
            address(Relation.BADGE_DEFINITION, badge.badgeDefinition, ATag.TAG_NAME)
            event(Relation.BADGE_AWARD, badge.badgeAward, ETag.TAG_NAME)
        }
        each(e.tags, ATag::parse) { if (it.kind == AcceptedBadgeSetEvent.KIND) address(Relation.BADGE_SET, it, ATag.TAG_NAME) }
    }
}
