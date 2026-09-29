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
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip72ModCommunities.approval.CommunityPostApprovalEvent
import com.vitorpamplona.quartz.nip72ModCommunities.approval.tags.ApprovedAddressTag
import com.vitorpamplona.quartz.nip72ModCommunities.approval.tags.ApprovedEventTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.ModeratorTag
import com.vitorpamplona.quartz.nip72ModCommunities.follow.CommunityListEvent
import com.vitorpamplona.quartz.nip72ModCommunities.follow.tags.CommunityTag
import com.vitorpamplona.quartz.nip72ModCommunities.rules.CommunityRulesEvent
import com.vitorpamplona.quartz.nip72ModCommunities.rules.tags.KindRuleTag
import com.vitorpamplona.quartz.nip72ModCommunities.rules.tags.PubkeyRuleTag
import com.vitorpamplona.quartz.nip72ModCommunities.rules.tags.WotTag

/** Quartz's `nip72ModCommunities` classes. */
internal fun KindMappers.Builder.nip72ModCommunities() {
    // NIP-72: the `a` to a kind 34550 is the `COMMUNITY` the approval is for; the other `e`/`a`
    // is the `APPROVED` post, the `p` its author and `k` its kind. The post's JSON in the content
    // is the same event as the `e`, not another link.
    on<CommunityPostApprovalEvent> { e ->
        each(e.tags, CommunityTag::parse) { address(Relation.COMMUNITY, it.address, CommunityTag.TAG_NAME) }
        each(e.tags, ApprovedEventTag::parse) { event(Relation.APPROVED, it.ref.eventId, ApprovedEventTag.TAG_NAME) }
        each(e.tags, ApprovedAddressTag::parse) { address(Relation.APPROVED, it.address, ApprovedAddressTag.TAG_NAME) }
        each(e.tags, PTag::parse) { user(Relation.APPROVED_AUTHOR, it, PTag.TAG_NAME) }
        each(e.tags, KindTag::parse) { tag(Relation.TAG, KindTag.TAG_NAME, it.toString()) }
    }

    // NIP-72: a `p` with the `moderator` role is a `MODERATOR`. A `p` with no role is one too, as
    // `moderators` reads every `p` (`nip72IsModerator`); a `p` with any other role is only a
    // `MENTION`. NIP-72 defines no `e`/`a`/`q` here: the ones Quartz reads as hints are mentions
    // and quotes. The `relay` tags are URLs.
    on<CommunityDefinitionEvent> { e ->
        each(
            e.tags,
            ModeratorTag::parse,
        ) { user(if (it.nip72IsModerator()) Relation.MODERATOR else Relation.MENTION, it, ModeratorTag.TAG_NAME) }
        each(e.tags, ETag::parse) { event(Relation.MENTION, it, ETag.TAG_NAME) }
        each(e.tags, ATag::parse) { address(Relation.MENTION, it, ATag.TAG_NAME) }
        quotes(e.tags)
    }

    // NIP-51: the NIP-72 communities (kind 34550 `a` tags) the user belongs to are `SUBSCRIBED`.
    on<CommunityListEvent> { e -> each(e.tags, CommunityTag::parse) { address(Relation.SUBSCRIBED, it.address, CommunityTag.TAG_NAME) } }

    // NIP-9B: the `a` is the `COMMUNITY` these rules govern. A `p` rule is `ALLOWED` or `DENIED`
    // (split because every query filters on it), with its `role` when it names one; a `wot` gate's
    // pubkey is the `WOT_ROOT` of a web of trust `depth` hops deep; `k` names an allowed kind.
    on<CommunityRulesEvent> { e ->
        each(e.tags, CommunityTag::parse) { address(Relation.COMMUNITY, it.address, CommunityTag.TAG_NAME) }
        each(e.tags, KindRuleTag::parse) { tag(Relation.TAG, KindRuleTag.TAG_NAME, it.kind.toString()) }
        each(e.tags, PubkeyRuleTag::parse) {
            // DENIED qualifies nothing: a deny rule's role, if it writes one, is no link prop.
            if (it.policy == PubkeyRuleTag.Policy.ALLOW) {
                user(Relation.ALLOWED, it.pubkey, PubkeyRuleTag.TAG_NAME, it.nip72LinkProps())
            } else {
                user(Relation.DENIED, it.pubkey, PubkeyRuleTag.TAG_NAME)
            }
        }
        each(e.tags, WotTag::parse) { user(Relation.WOT_ROOT, it.rootPubkey, WotTag.TAG_NAME, it.nip72LinkProps()) }
    }
}
