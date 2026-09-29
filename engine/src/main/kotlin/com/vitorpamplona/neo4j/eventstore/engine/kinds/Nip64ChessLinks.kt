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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.ChessResultProps
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip64Chess.baseEvent.BaseChessEvent
import com.vitorpamplona.quartz.nip64Chess.baseEvent.tags.OpponentTag
import com.vitorpamplona.quartz.nip64Chess.challenge.accept.LiveChessGameAcceptEvent
import com.vitorpamplona.quartz.nip64Chess.challenge.accept.tags.ChallengeEventTag
import com.vitorpamplona.quartz.nip64Chess.end.LiveChessGameEndEvent
import com.vitorpamplona.quartz.nip64Chess.end.tags.WinnerTag
import com.vitorpamplona.quartz.nip64Chess.game.ChessGameEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterProtocol

/** Quartz's `nip64Chess` classes. */
internal fun KindMappers.Builder.nip64Chess() {
    // Every live chess event names the other player in its `p` tag (the challenge, move and draw offer inherit this).
    on<BaseChessEvent> { e -> each(e.tags, OpponentTag::parse) { user(Relation.OPPONENT, it, OpponentTag.TAG_NAME) } }

    // The accepted challenge (`e`) and the challenger (`p`).
    on<LiveChessGameAcceptEvent> { e ->
        each(e.tags, ChallengeEventTag::parse) { event(Relation.ACCEPTED, it, ChallengeEventTag.TAG_NAME) }
        each(e.tags, OpponentTag::parse) { user(Relation.OPPONENT, it, OpponentTag.TAG_NAME) }
    }

    // The opponent and the winner, when there is one. The result and the termination reason ride on both.
    on<LiveChessGameEndEvent> { e ->
        val props = ChessResultProps(e.result(), e.termination())
        each(e.tags, OpponentTag::parse) { user(Relation.OPPONENT, it, OpponentTag.TAG_NAME, props) }
        user(Relation.WINNER, e.winnerPubkey(), WinnerTag.TAG_NAME, props)
    }

    // Jester threads a game by position: the first `e` is the game's start event and the second the
    // previous move. A start event's only `e` is [JesterProtocol.START_POSITION_HASH], a hash of the
    // starting board rather than an event id, so it is not a link.
    on<JesterEvent> { e ->
        val eTags = e.tags.mapNotNull(ETag::parse)
        event(Relation.ROOT, eTags.firstOrNull()?.takeUnless { it.eventId == JesterProtocol.START_POSITION_HASH }, ETag.TAG_NAME)
        event(Relation.PARENT, eTags.getOrNull(1)?.takeUnless { it.eventId == JesterProtocol.START_POSITION_HASH }, ETag.TAG_NAME)
        each(e.tags, PTag::parse) { user(Relation.OPPONENT, it, PTag.TAG_NAME) }
    }

    free<ChessGameEvent>()
}
