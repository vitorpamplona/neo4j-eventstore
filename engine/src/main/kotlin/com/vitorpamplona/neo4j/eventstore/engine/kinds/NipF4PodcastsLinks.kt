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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.UserTag
import com.vitorpamplona.quartz.nipF4Podcasts.authored.AuthoredPodcastsEvent
import com.vitorpamplona.quartz.nipF4Podcasts.episode.PodcastEpisodeEvent
import com.vitorpamplona.quartz.nipF4Podcasts.favorites.FavoritePodcastsListEvent
import com.vitorpamplona.quartz.nipF4Podcasts.metadata.PodcastMetadataEvent
import com.vitorpamplona.quartz.nipF4Podcasts.metadata.tags.AuthorTag

/** Quartz's `nipF4Podcasts` classes. */
internal fun KindMappers.Builder.nipF4Podcasts() {
    // NIP-F4: the podcasts this user authors, the counter-claim a podcast's 10154 authors are verified against.
    on<AuthoredPodcastsEvent> { e -> each(e.tags, UserTag::parseKey) { user(Relation.AUTHORED, it, UserTag.TAG_NAME) } }

    // The public favorites; the encrypted ones stay private.
    on<FavoritePodcastsListEvent> { e -> each(e.tags, UserTag::parseKey) { user(Relation.FAVORITE, it, UserTag.TAG_NAME) } }

    // NIP-F4: the people the podcast (the signer) claims as its authors, with their role. The claim holds only when their 10064 names the podcast back.
    on<PodcastMetadataEvent> { e ->
        e.claimedAuthors().forEach { user(Relation.PODCAST_AUTHOR, it.pubKey, AuthorTag.TAG_NAME, RoleProps(listOfNotNull(it.role))) }
    }

    free<PodcastEpisodeEvent>()
}
