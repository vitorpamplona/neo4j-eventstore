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
import com.vitorpamplona.neo4j.eventstore.engine.vocab.ValueType
import com.vitorpamplona.neo4j.eventstore.engine.vocab.each
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootEventTag
import com.vitorpamplona.quartz.nip34Git.grasp.UserGraspListEvent
import com.vitorpamplona.quartz.nip34Git.issue.GitIssueEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestUpdateEvent
import com.vitorpamplona.quartz.nip34Git.reply.GitReplyEvent
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.repository.tags.MaintainersTag
import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusAppliedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusEvent

/** Quartz's `nip34Git` classes. */
internal fun KindMappers.Builder.nip34Git() {
    on<GitIssueEvent> { e ->
        nip34GitPeopleLinks(e.tags, nip34RepositoryLinks(e.tags))
        quotes(e.tags)
        hashtags(e.tags)
        contentMentions(e.content)
    }

    // NIP-34: the repository (`a`) and its owner's `p`; a series is threaded by marked `e` tags
    // (`reply` points at the previous patch, `root` at the series' first). `t` holds the `root` and
    // `root-revision` markers, which say what the patch is (`isRoot()`), not a topic: only other
    // `t`s are hashtags (compared lowercased, as a hashtag's identity is). `r` is the earliest unique commit.
    on<GitPatchEvent> { e ->
        nip34GitPeopleLinks(e.tags, nip34RepositoryLinks(e.tags))
        each(e.tags, MarkedETag::parseAllThreadTags) {
            when (it.marker) {
                MarkedETag.MARKER.ROOT -> event(Relation.ROOT, it, MarkedETag.TAG_NAME)
                MarkedETag.MARKER.REPLY -> event(Relation.PARENT, it, MarkedETag.TAG_NAME)
                else -> event(Relation.MENTION, it, MarkedETag.TAG_NAME)
            }
        }
        each(e.tags, HashtagTag::parseLowercase) {
            if (it != GitPatchEvent.ROOT &&
                it != GitPatchEvent.ROOT_REVISION
            ) {
                value(Relation.HASHTAG, ValueType.HASHTAG, it, HashtagTag.TAG_NAME)
            }
        }
        nip34CommitLinks(e.tags)
    }

    // NIP-34: the repository and its owner, the root patch this PR revises (`e`), labels (`t`) and the earliest unique commit (`r`).
    on<GitPullRequestEvent> { e ->
        nip34GitPeopleLinks(e.tags, nip34RepositoryLinks(e.tags))
        each(e.tags, ETag::parse) { event(Relation.REVISED, it, ETag.TAG_NAME) }
        hashtags(e.tags)
        nip34CommitLinks(e.tags)
    }

    // NIP-34 names the updated pull request with NIP-22 root tags: `E` and its author `P`.
    on<GitPullRequestUpdateEvent> { e ->
        each(e.tags, RootEventTag::parseKey) { event(Relation.ROOT, it, RootEventTag.TAG_NAME) }
        each(e.tags, RootAuthorTag::parseKey) { user(Relation.ROOT_AUTHOR, it, RootAuthorTag.TAG_NAME) }
        nip34GitPeopleLinks(e.tags, nip34RepositoryLinks(e.tags))
        nip34CommitLinks(e.tags)
    }

    // The repository, the NIP-10 thread (the root is the issue or patch), notified people, quotes and citations.
    on<GitReplyEvent> { e ->
        nip34RepositoryLinks(e.tags)
        nip34ThreadLinks(e.threadTags())
        each(e.tags, PTag::parse) { user(Relation.MENTION, it, PTag.TAG_NAME) }
        quotes(e.tags)
        contentMentions(e.content)
    }

    // NIP-34: the other maintainers, topics (including the `personal-fork` marker), the earliest
    // unique commit, and the repository this one is a fork of (`u`, when it holds an address rather
    // than a git URL).
    on<GitRepositoryEvent> { e ->
        e.maintainers().forEach { user(Relation.MAINTAINER, it, MaintainersTag.TAG_NAME) }
        hashtags(e.tags)
        value(Relation.REPOSITORY, ValueType.GIT_COMMIT, e.earliestUniqueCommit(), Nip34CommitTag.TAG_NAME)
        each(e.tags, Nip34UpstreamTag::parse) { address(Relation.FORK, it, Nip34UpstreamTag.TAG_NAME) }
    }

    // The open, closed and draft statuses (1630, 1632, 1633) link alike: registered once at their base.
    on<GitStatusEvent> { e -> nip34StatusLinks(e) }

    // A 1631 also names the patches it applied or merged, in `q` tags that are not NIP-18 quotes.
    on<GitStatusAppliedEvent> { e ->
        nip34StatusLinks(e)
        each(e.tags, QEventTag::parse) { event(Relation.APPLIED, it.eventId, QEventTag.TAG_NAME) }
    }

    free<UserGraspListEvent>()
    free<GitRepositoryStateEvent>()
}

/**
 * NIP-34 statuses: the `e` marked `root` is the issue, PR or patch, the `e` marked `reply` the
 * accepted revision. Their authors are read from the `e` tags' author slot so the unmarked `p`
 * tags can be told apart (see [nip34GitPeopleLinks]).
 */
private fun LinkBuilder.nip34StatusLinks(e: GitStatusEvent) {
    var rootAuthor: HexKey? = null
    var parentAuthor: HexKey? = null
    each(e.tags, MarkedETag::parseAllThreadTags) {
        when (it.marker) {
            MarkedETag.MARKER.ROOT -> {
                event(Relation.ROOT, it, MarkedETag.TAG_NAME)
                if (rootAuthor == null) rootAuthor = LinkBuilder.normalizedHex(it.author)
            }

            MarkedETag.MARKER.REPLY -> {
                event(Relation.PARENT, it, MarkedETag.TAG_NAME)
                if (parentAuthor == null) parentAuthor = LinkBuilder.normalizedHex(it.author)
            }

            else -> {
                event(Relation.MENTION, it, MarkedETag.TAG_NAME)
            }
        }
    }
    nip34GitPeopleLinks(e.tags, nip34RepositoryLinks(e.tags), rootAuthor, parentAuthor)
    nip34CommitLinks(e.tags)
}

/**
 * NIP-10 threading: marked `e` tags say their role, and a thread with a `root` but no `reply`
 * replies to the root. Unmarked tags are positional: the first is the root, the last the parent,
 * the ones between are mentions.
 */
private fun LinkBuilder.nip34ThreadLinks(thread: List<MarkedETag>) {
    if (thread.isEmpty()) return
    if (thread.any { it.marker == MarkedETag.MARKER.ROOT || it.marker == MarkedETag.MARKER.REPLY }) {
        var root: MarkedETag? = null
        var hasParent = false
        thread.forEach {
            when (it.marker) {
                MarkedETag.MARKER.ROOT -> {
                    event(Relation.ROOT, it, MarkedETag.TAG_NAME)
                    if (root == null) root = it
                }

                MarkedETag.MARKER.REPLY -> {
                    event(Relation.PARENT, it, MarkedETag.TAG_NAME)
                    hasParent = true
                }

                else -> {
                    event(Relation.MENTION, it, MarkedETag.TAG_NAME)
                }
            }
        }
        if (!hasParent) event(Relation.PARENT, root, MarkedETag.TAG_NAME)
    } else {
        thread.forEachIndexed { index, it ->
            if (index == 0) event(Relation.ROOT, it, MarkedETag.TAG_NAME)
            if (index == thread.lastIndex) event(Relation.PARENT, it, MarkedETag.TAG_NAME)
            if (index != 0 && index != thread.lastIndex) event(Relation.MENTION, it, MarkedETag.TAG_NAME)
        }
    }
}

/**
 * NIP-34 names the repository an event is about in its `a` tags (one per maintainer's
 * announcement). Emits them as [Relation.REPOSITORY] and returns their authors, the repository
 * owners that [nip34GitPeopleLinks] tells apart from the other `p` tags.
 */
private fun LinkBuilder.nip34RepositoryLinks(tags: TagArray): Set<HexKey> {
    val owners = mutableSetOf<HexKey>()
    each(tags, ATag::parse) {
        address(Relation.REPOSITORY, it, ATag.TAG_NAME)
        LinkBuilder.normalizedHex(it.pubKeyHex)?.let { owner -> owners.add(owner) }
    }
    return owners
}

/**
 * NIP-34 `p` tags carry no marker: the repository owner, the root event's author and the
 * revision's author are told apart only by comparing each key with the pubkeys the `a` and `e`
 * tags name. A key can hold several of those roles at once; one that holds none is a plain
 * notification, [Relation.MENTION].
 */
private fun LinkBuilder.nip34GitPeopleLinks(
    tags: TagArray,
    owners: Set<HexKey>,
    rootAuthor: HexKey? = null,
    parentAuthor: HexKey? = null,
) = each(tags, PTag::parseKey) {
    val key = LinkBuilder.normalizedHex(it) ?: return@each
    var named = false
    if (key in owners) {
        user(Relation.REPOSITORY_OWNER, key, PTag.TAG_NAME)
        named = true
    }
    if (key == rootAuthor) {
        user(Relation.ROOT_AUTHOR, key, PTag.TAG_NAME)
        named = true
    }
    if (key == parentAuthor) {
        user(Relation.PARENT_AUTHOR, key, PTag.TAG_NAME)
        named = true
    }
    if (!named) user(Relation.MENTION, key, PTag.TAG_NAME)
}

/** The earliest unique commit NIP-34 names the target repository by, as its [Relation.REPOSITORY]. */
private fun LinkBuilder.nip34CommitLinks(tags: TagArray) =
    each(tags, Nip34CommitTag::parseReference) { value(Relation.REPOSITORY, ValueType.GIT_COMMIT, it, Nip34CommitTag.TAG_NAME) }
