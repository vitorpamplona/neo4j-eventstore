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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.tags.KeyPackageRefTag
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip02Welcome.tags.KeyPackageEventTag
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.tags.GroupIdTag
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.NotificationRequestEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenListEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenRemovalEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenRequestEvent

/** Quartz's `marmot` classes. */
internal fun KindMappers.Builder.marmot() {
    // The KeyPackageRef (`i`), the lookup key a Welcome's inviter resolves.
    on<KeyPackageEvent> { e -> value(Relation.KEY_PACKAGE_REF, ValueType.KEY_PACKAGE_REF, e.keyPackageRef(), KeyPackageRefTag.TAG_NAME) }
    free<KeyPackageRelayListEvent>()

    // The KeyPackage this Welcome consumed, and the group (its `h`: the Marmot nostr_group_id,
    // a random global id). This is an unsigned rumor inside a gift wrap, so only the recipient
    // ever sees these links.
    on<WelcomeEvent> { e ->
        event(Relation.KEY_PACKAGE, e.keyPackageEventId(), KeyPackageEventTag.TAG_NAME)
        each(e.tags, GroupIdTag::parse) { value(Relation.GROUP, ValueType.GROUP, it, GroupIdTag.TAG_NAME) }
    }

    // The group, by its `h` (the nostr_group_id, a random global id). The signer is a fresh
    // ephemeral key per event, so the `AUTHOR` link every event states is a throwaway here; the
    // inner rumors carry their own links once decrypted.
    on<GroupEvent> { e -> each(e.tags, GroupIdTag::parse) { value(Relation.GROUP, ValueType.GROUP, it, GroupIdTag.TAG_NAME) } }

    free<NotificationRequestEvent>()
    // Member and server keys live in the JSON content (an inner group payload), which a mapper does not parse.
    free<TokenListEvent>()
    free<TokenRemovalEvent>()
    free<TokenRequestEvent>()
}
