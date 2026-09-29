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
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.concurrent.ConcurrentHashMap

/** What one Quartz event class states: its links, read through Quartz's Tag parsers and accessors. */
typealias Mapper<E> = LinkBuilder.(E) -> Unit

/**
 * Every Quartz event class's [Mapper], by class: the per-kind half of the vocabulary
 * (`docs/vocabulary.md`). A class with nothing to say is registered link-free, so "no mapper"
 * always means "not decided yet", and `KindMappersCoverageTest` fails on it for every class the
 * pinned Quartz's `EventFactory` builds.
 *
 * A class inherits its nearest registered superclass's mapper, so a family whose members all
 * link alike is registered once at its base.
 */
class KindMappers private constructor(
    private val byClass: Map<Class<*>, Mapper<Event>>,
) {
    private val resolved = ConcurrentHashMap<Class<*>, Resolution>()

    private class Resolution(
        val mapper: Mapper<Event>?,
    )

    /** The mapper for [klass] or its nearest registered superclass; null when none decided. */
    fun mapperFor(klass: Class<*>): Mapper<Event>? =
        resolved
            .getOrPut(klass) {
                var c: Class<*>? = klass
                var found: Mapper<Event>? = null
                while (c != null && found == null) {
                    found = byClass[c]
                    c = c.superclass
                }
                Resolution(found)
            }.mapper

    /** The classes registered directly. */
    val registered: Set<Class<*>> get() = byClass.keys

    class Builder {
        @PublishedApi
        internal val byClass = HashMap<Class<*>, Mapper<Event>>()

        /** [E]'s links. */
        inline fun <reified E : Event> on(noinline mapper: Mapper<E>) {
            @Suppress("UNCHECKED_CAST")
            register(E::class.java, mapper as Mapper<Event>)
        }

        /** [E] references nothing: settings, key packages, relay lists, ephemeral auth. */
        inline fun <reified E : Event> free() = register(E::class.java, NO_LINKS)

        @PublishedApi
        internal fun register(
            klass: Class<*>,
            mapper: Mapper<Event>,
        ) {
            require(byClass.put(klass, mapper) == null) { "two mappers for ${klass.name}" }
        }

        fun build() = KindMappers(HashMap(byClass))
    }

    companion object {
        @PublishedApi
        internal val NO_LINKS: Mapper<Event> = { }

        fun build(block: Builder.() -> Unit): KindMappers = Builder().apply(block).build()
    }
}
