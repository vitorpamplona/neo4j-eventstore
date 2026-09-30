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

import com.vitorpamplona.quartz.nip51Lists.bookmarkList.OldBookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.AddressBookmark
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.BookmarkIdTag
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark
import com.vitorpamplona.quartz.nip51Lists.kindMuteSet.KindMuteSetEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.EventTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.MuteTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.UserTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.WordTag
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.HashtagTag as MutedHashtagTag

/** The `d` values of the deprecated kind 30001 ([OldBookmarkListEvent]) that name the list it stood for; main names none. */
internal object Nip51OldBookmarkDTags {
    const val PIN_D_TAG = "pin"
    const val COMMUNITIES_D_TAG = "communities"
}

/** The kind a [KindMuteSetEvent] mutes its users for: its `d`. Main has no accessor for it. */
internal fun KindMuteSetEvent.nip51MutedKind() = dTag().toIntOrNull()

/**
 * NIP-51 list items read without their relay hints, which no link uses: main's `parse` of each
 * normalizes the hint through Quartz's global, synchronized relay-url cache, and a mute list or a
 * follow set can hold thousands of entries. Each reads the same tags as main's parser, into the
 * same class, only with the hint left empty.
 */
internal object Nip51ListItems {
    /** A bookmark-like list's `e` or `a` item, as [BookmarkIdTag.parse] reads it. */
    fun parseBookmark(tag: Array<String>): BookmarkIdTag? =
        EventBookmark.parseId(tag)?.let { EventBookmark(it) }
            ?: AddressBookmark.parseAddress(tag)?.let { AddressBookmark(it) }

    /** A mute list's entry (`word`, `p`, `e`, `t`), as [MuteTag.parse] reads it. */
    fun parseMute(tag: Array<String>): MuteTag? =
        WordTag.parse(tag)
            ?: UserTag.parseKey(tag)?.let { UserTag(it) }
            ?: EventTag.parseId(tag)?.let { EventTag(it) }
            ?: MutedHashtagTag.parse(tag)
}
