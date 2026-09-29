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
package com.vitorpamplona.neo4j.eventstore.engine.vocab.props

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PropsColumns] is the whole edge-property schema: every props class, with every field set,
 * writes only keys the table lists, with the table's type. Fields are filled by reflection over
 * each data class's constructor, so a new field is covered the moment it exists.
 */
class PropsColumnsTest {
    private fun propsClasses(): List<Class<*>> {
        val root = listOf(File("src/main/kotlin"), File("engine/src/main/kotlin")).first { it.isDirectory }
        val dir = File(root, "com/vitorpamplona/neo4j/eventstore/engine/vocab/props")
        val decl = Regex("""^data class (\w+)\(""", RegexOption.MULTILINE)
        return dir
            .listFiles { f -> f.extension == "kt" }!!
            .flatMap { f -> decl.findAll(f.readText()).map { it.groupValues[1] } }
            .map { Class.forName("com.vitorpamplona.neo4j.eventstore.engine.vocab.props.$it") }
    }

    private fun sample(type: Class<*>): Any =
        when (type) {
            String::class.java -> "x"
            java.lang.Integer::class.java, Int::class.javaPrimitiveType -> 1
            java.lang.Long::class.java, Long::class.javaPrimitiveType -> 1L
            java.lang.Double::class.java, Double::class.javaPrimitiveType -> 1.0
            java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType -> true
            List::class.java -> listOf("x")
            else -> error("no sample for $type")
        }

    private fun typeOf(value: Any): PropType =
        when (value) {
            is String -> PropType.STRING
            is Int, is Long -> PropType.LONG
            is Double -> PropType.DOUBLE
            is Boolean -> PropType.BOOLEAN
            is List<*> -> PropType.STRING_LIST
            else -> error("unsupported prop value ${value::class}")
        }

    @Test
    fun everyPropsKeyIsAColumnOfItsType() {
        val classes = propsClasses()
        assertTrue(classes.size > 30, "found only ${classes.size} props classes")
        val seen = HashSet<String>()
        for (klass in classes) {
            val ctor =
                klass.constructors
                    .filter { c -> c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
                    .maxBy { it.parameterCount }
            val props = ctor.newInstance(*ctor.parameterTypes.map { sample(it) }.toTypedArray()) as LinkProps
            val map = props.toMap()
            assertEquals(ctor.parameterCount, map.size, "${klass.simpleName}: every field set, but not every one written")
            for ((key, value) in map) {
                assertEquals(PropsColumns.TYPES[key], typeOf(value), "${klass.simpleName}.$key")
                seen += key
            }
        }
        assertEquals(PropsColumns.TYPES.keys, seen, "columns no props class writes")
    }
}
