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

import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.RoleProps
import com.vitorpamplona.neo4j.eventstore.engine.vocab.props.WotProps
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.ModeratorTag
import com.vitorpamplona.quartz.nip72ModCommunities.rules.tags.PubkeyRuleTag
import com.vitorpamplona.quartz.nip72ModCommunities.rules.tags.WotTag

/** NIP-72's moderator role, which main's [ModeratorTag] does not name. */
internal const val NIP72_MODERATOR_ROLE = "moderator"

/**
 * NIP-72 lists moderators as `p` tags with the `moderator` role. A `p` with no role is one
 * too, as the community's `moderators()` reads every `p`; any other role is not. Main's
 * [ModeratorTag] has no `isModerator()`.
 */
internal fun ModeratorTag.nip72IsModerator() = role.isNullOrBlank() || role.equals(NIP72_MODERATOR_ROLE, ignoreCase = true)

/** The role a rule grants, on the link to the pubkey it names (main's [PubkeyRuleTag] has no `linkProps()`). */
internal fun PubkeyRuleTag.nip72LinkProps() = RoleProps(listOfNotNull(role))

/** How many hops from the root a poster must be, on the link to that root (main's [WotTag] has no `linkProps()`). */
internal fun WotTag.nip72LinkProps() = WotProps(depth)
