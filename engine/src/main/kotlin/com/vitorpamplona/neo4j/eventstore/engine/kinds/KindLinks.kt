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

import com.vitorpamplona.neo4j.eventstore.engine.schema.assembleAddress
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Link
import com.vitorpamplona.neo4j.eventstore.engine.vocab.Relation
import com.vitorpamplona.neo4j.eventstore.engine.vocab.links
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable

/**
 * An event's links: the vocabulary applied to one event (`docs/vocabulary.md`).
 *
 * The links every event states come first — its `AUTHOR`, its `ADDRESS` (replaceable and
 * addressable kinds: the coordinate other events' `a` tags point at, and the slot the NIP-01
 * supersession rule is decided on), and the tags any kind may carry ([everyKindLinks]) — then
 * its class's [Mapper]. An event whose kind the pinned Quartz has no class for states only the
 * first three: there is no fallback that guesses what a tag means from its shape.
 */
object KindLinks {
    /** Every Quartz package's mappers, one registration function per package (`<Package>Links.kt`). */
    val mappers: KindMappers =
        KindMappers.build {
            buzz()
            concord()
            contextvm()
            cyberspace()
            experimental()
            marmot()
            nip01Core()
            nip02FollowList()
            nip03Timestamp()
            nip04Dm()
            nip09Deletions()
            nip10Notes()
            nip15Marketplace()
            nip17Dm()
            nip18Reposts()
            nip22Comments()
            nip23LongContent()
            nip25Reactions()
            nip28PublicChat()
            nip29RelayGroups()
            nip30CustomEmoji()
            nip32Labeling()
            nip34Git()
            nip35Torrents()
            nip37Drafts()
            nip38UserStatus()
            nip39ExtIdentities()
            nip42RelayAuth()
            nip43RelayMembers()
            nip46RemoteSigner()
            nip47WalletConnect()
            nip50Search()
            nip51Lists()
            nip52Calendar()
            nip53LiveActivities()
            nip54Wiki()
            nip56Reports()
            nip57Zaps()
            nip58Badges()
            nip59Giftwrap()
            nip5aStaticWebsites()
            nip5dNapplets()
            nip60Cashu()
            nip61Nutzaps()
            nip62RequestToVanish()
            nip64Chess()
            nip65RelayList()
            nip66RelayMonitor()
            nip68Picture()
            nip69P2pOrderEvents()
            nip71Video()
            nip72ModCommunities()
            nip75ZapGoals()
            nip78AppData()
            nip7DThreads()
            nip84Highlights()
            nip85TrustedAssertions()
            nip87Ecash()
            nip88Polls()
            nip89AppHandlers()
            nip90Dvms()
            nip94FileMetadata()
            nip96FileStorage()
            nip98HttpAuth()
            nip99Classifieds()
            nipA0VoiceMessages()
            nipA3PaymentTargets()
            nipA4PublicMessages()
            nipACWebRtcCalls()
            nipB0WebBookmarks()
            nipB1Bolt12Zaps()
            nipB7Blossom()
            nipBCOnchainZaps()
            nipC0CodeSnippets()
            nipC7Chats()
            nipCCGeocaching()
            nipF4Podcasts()
            nipXXPodcasting20()
            nipXXPushNotifications()
        }

    /**
     * [event]'s links. [event] must be Quartz's typed class for its kind (`EventFactory`); a
     * plain [Event] states only the common links. A mapper that throws on a malformed event
     * contributes the links it built before the throw: the rest of the event's graph survives.
     */
    fun of(event: Event): List<Link<*>> =
        links {
            user(Relation.AUTHOR, event.pubKey)
            ownAddress(event)?.let { address(Relation.ADDRESS, it) }
            everyKindLinks(event.tags)
            mappers.mapperFor(event.javaClass)?.let { mapper -> runCatching { mapper(event) } }
        }

    /**
     * The one slot [event] competes for under NIP-01: `kind:pubkey:` for replaceable kinds,
     * `kind:pubkey:d` (the FIRST `d`) for addressable ones; null for every other kind. Read from
     * the kind range, not from Quartz's `AddressableEvent`, so a stray `d` on a replaceable list
     * cannot split its slot.
     */
    fun ownAddress(event: Event): String? =
        when {
            event.kind.isAddressable() -> assembleAddress(event.kind, event.pubKey, firstD(event) ?: "")
            event.kind.isReplaceable() -> assembleAddress(event.kind, event.pubKey, "")
            else -> null
        }

    private fun firstD(event: Event): String? {
        for (tag in event.tags) if (tag.size >= 2 && tag[0] == "d") return tag[1]
        return null
    }
}
