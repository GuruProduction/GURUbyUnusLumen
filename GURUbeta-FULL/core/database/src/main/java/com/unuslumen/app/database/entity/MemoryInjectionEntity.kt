// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per memory the preamble builder injected into a model turn.
 *
 * This table is the labelling asset for retrieval quality. It records what the
 * system believed was relevant, for which query, in which conversation. Nothing
 * else in the app records that, which meant there was no way to measure whether
 * retrieval was working on real user data: the only evidence was a handful of
 * hand-written test pairs.
 *
 * How it becomes labels, without a model judging itself:
 *
 *  1. A memory injected for a turn and never referenced again in that
 *     conversation is a suspected false positive.
 *  2. A user restating a fact the preamble did not inject ("I told you, the key
 *     is in the kitchen") is a labelled recall failure, for free, from the real
 *     distribution.
 *  3. A memory injected and then echoed back by the user or acted on by the
 *     model is a confirmed hit.
 *
 * Row 2 is the valuable one. It is ground truth the user produced by accident,
 * and it costs nothing to collect. It only exists if injection is logged, which
 * is why this table exists before the semantic lane rather than after it.
 *
 * Privacy: this table holds memory ids and query text only, never memory bodies.
 * A row says "memory X was believed relevant to this query", which is derivable
 * from the brain and the conversation anyway. It stays on device with everything
 * else and is pruned on the same schedule as tool results.
 */
@Entity(
    tableName = "memory_injections",
    indices = [
        Index("conversation_id"),
        Index("timestamp"),
        Index("memory_id"),
        Index("lane"),
    ]
)
data class MemoryInjectionEntity(
    @PrimaryKey
    val id: String,

    /** The 64-hex brain id of the memory that was injected. */
    @ColumnInfo(name = "memory_id")
    val memoryId: String,

    /** The user message the preamble was built for. */
    @ColumnInfo(name = "query")
    val query: String,

    /** Which lane supplied it: "fact", "message" or "toolResult". */
    @ColumnInfo(name = "lane")
    val lane: String,

    /** 1-based position within its lane, so rank can be evaluated, not just presence. */
    @ColumnInfo(name = "rank_in_lane")
    val rankInLane: Int,

    /** The lane's own score for this hit, as the brain reported it. */
    @ColumnInfo(name = "score")
    val score: Float,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /**
     * Set when the conversation shows this memory being used: echoed by the
     * user, or its content acted on by the model. Null means "injected, no
     * evidence either way yet".
     */
    @ColumnInfo(name = "confirmed_used_at")
    val confirmedUsedAt: Long? = null,

    /**
     * Set when a later user message restates this memory's content while the
     * preamble did NOT inject it. That is a recall failure the user produced by
     * accident, and it is the most valuable row type in the table.
     */
    @ColumnInfo(name = "restated_without_injection")
    val restatedWithoutInjection: Boolean = false
) {
    companion object {
        /** Lanes as the preamble builder names them. */
        const val LANE_FACT = "fact"
        const val LANE_MESSAGE = "message"
        const val LANE_TOOL_RESULT = "toolResult"

        /** Query text is capped so one huge message cannot bloat the ledger. */
        const val MAX_QUERY_CHARS = 2000

        fun capQuery(query: String): String =
            if (query.length <= MAX_QUERY_CHARS) query else query.take(MAX_QUERY_CHARS)
    }
}
