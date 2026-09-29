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

/** The store type of one edge property, as Neo4j holds it. */
enum class PropType(
    /** The `neo4j-admin import` header type. */
    val csv: String,
) {
    STRING("string"),
    LONG("long"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    STRING_LIST("string[]"),
}

/**
 * Every key a [LinkProps] class can write, with its store type: the edge-property schema in one
 * table. Readers that need a fixed set of columns (the bulk importer's CSV header) take it from
 * here; `PropsColumnsTest` fills every props class's every field and fails on a key missing
 * from this table or written with another type, so a new props field cannot skip it.
 */
object PropsColumns {
    val TYPES: Map<String, PropType> =
        linkedMapOf(
            "action" to PropType.STRING,
            "active_hours_end" to PropType.LONG,
            "active_hours_start" to PropType.LONG,
            "amount" to PropType.DOUBLE,
            "comment_cnt" to PropType.LONG,
            "conditions" to PropType.STRING,
            "credit" to PropType.STRING,
            "depth" to PropType.LONG,
            "direction" to PropType.STRING,
            "duration_extension" to PropType.LONG,
            "expiration" to PropType.LONG,
            "fb" to PropType.STRING,
            "first_created_at" to PropType.LONG,
            "followers" to PropType.LONG,
            "frame" to PropType.STRING,
            "hops" to PropType.LONG,
            "labels" to PropType.STRING_LIST,
            "level" to PropType.LONG,
            "mark" to PropType.STRING,
            "msats" to PropType.LONG,
            "muted_kind" to PropType.LONG,
            "order" to PropType.LONG,
            "path" to PropType.STRING,
            "phase" to PropType.STRING,
            "platform" to PropType.STRING,
            "polarity" to PropType.DOUBLE,
            "post_cnt" to PropType.LONG,
            "proof" to PropType.STRING,
            "quote_cnt" to PropType.LONG,
            "rank" to PropType.LONG,
            "reaction_cnt" to PropType.LONG,
            "reactions_cnt" to PropType.LONG,
            "reason" to PropType.STRING,
            "release" to PropType.STRING,
            "reply_cnt" to PropType.LONG,
            "report" to PropType.STRING,
            "report_raw" to PropType.STRING,
            "reports_cnt_recd" to PropType.LONG,
            "reports_cnt_sent" to PropType.LONG,
            "repost_cnt" to PropType.LONG,
            "responses" to PropType.STRING_LIST,
            "result" to PropType.STRING,
            "roles" to PropType.STRING_LIST,
            "score" to PropType.LONG,
            "service" to PropType.STRING,
            "stars" to PropType.DOUBLE,
            "status" to PropType.STRING,
            "termination" to PropType.STRING,
            "title" to PropType.STRING,
            "verified" to PropType.BOOLEAN,
            "weight" to PropType.DOUBLE,
            "x" to PropType.LONG,
            "y" to PropType.LONG,
            "zap_amount" to PropType.LONG,
            "zap_amt_recd" to PropType.LONG,
            "zap_amt_sent" to PropType.LONG,
            "zap_avg_amt_day_recd" to PropType.LONG,
            "zap_avg_amt_day_sent" to PropType.LONG,
            "zap_cnt" to PropType.LONG,
            "zap_cnt_recd" to PropType.LONG,
            "zap_cnt_sent" to PropType.LONG,
        )
}
