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
package com.vitorpamplona.neo4j.eventstore.engine.client

import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.ALICE
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.BOB
import com.vitorpamplona.neo4j.eventstore.engine.Fixtures.event
import com.vitorpamplona.neo4j.eventstore.engine.derive.EdgeDeriver
import com.vitorpamplona.neo4j.eventstore.engine.derive.NodeKind
import kotlin.test.Test
import kotlin.test.assertTrue

class Neo4jGraphIndexTest {
    @Test
    fun whatADisplacingApplyKeepsIncludesTheOwnersOfTheAddressesItReferences() {
        // A bookmark list whose new version bookmarks another of Bob's articles: the old
        // version's address is orphaned and takes Bob with it — unless Bob is kept, since the new
        // version's address group re-MERGEs him in the same transaction.
        val list = event(10003, ALICE, listOf(listOf("a", "30023:$BOB:new")), createdAt = 200)
        val keep = Neo4jGraphIndex.keepFor(EdgeDeriver().derive(list))
        assertTrue(NodeKind.ADDRESS to "30023:$BOB:new" in keep)
        assertTrue(NodeKind.USER to BOB in keep, "the referenced address's owner: $keep")
        assertTrue(NodeKind.EVENT to list.id in keep, "the new version's own node")
        assertTrue(NodeKind.USER to ALICE in keep, "its author")
    }
}
