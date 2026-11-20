// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One piece of information that was considered for, or given to, an agent's work.
 *
 * THE QUESTION THIS ANSWERS. "Why did it say that?" The specification's section 8.2 lists exactly
 * what makes that answerable: source identifier, source type, retrieval time, the relevant passage,
 * the permission scope, and whether it was actually supplied to the model. GURU's existing
 * `memory_injections` table does this for the main chat and nothing does it for an agent, so an
 * agent's answer has no provenance trail at all today.
 *
 * [suppliedToModel] IS THE FIELD THAT STOPS A LIE. A retrieval that found something and then did not
 * fit in the context window is not the same as one that was read and used. Without this flag, every
 * candidate looks like an influence, which would let the room claim the agent read something it
 * never saw.
 *
 * THE PASSAGE IS AN EXCERPT, NOT THE WHOLE DOCUMENT. Enough to recognise what it was and to check
 * the claim, bounded at the write site. The full content stays in whatever store owns it, reachable
 * through [sourceRef].
 */
@Entity(
    tableName = "agent_context_references",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "created_at"], name = "index_agent_context_run_id_created_at"),
        Index(value = ["run_id", "supplied_to_model"], name = "index_agent_context_run_id_supplied"),
        Index(value = ["source_kind", "source_ref"], name = "index_agent_context_source"),
    ],
)
data class AgentContextReferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "reference_id")
    val referenceId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    /** What kind of source: a memory, a note, a project document, a message, a tool result, a file. */
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,

    /** The owning subsystem's identifier for that source. */
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,

    /** A human-readable label, so a reader can recognise the source without opening it. */
    @ColumnInfo(name = "source_label", defaultValue = "")
    val sourceLabel: String = "",

    /** The query or reason this was retrieved for. */
    @ColumnInfo(name = "retrieval_query", defaultValue = "")
    val retrievalQuery: String = "",

    /** Whether the agent's memory scope permitted this source at all. */
    @ColumnInfo(name = "within_scope", defaultValue = "1")
    val withinScope: Boolean = true,

    /** The permission scope this was retrieved under, for audit. */
    @ColumnInfo(name = "scope_label", defaultValue = "")
    val scopeLabel: String = "",

    /**
     * Did this actually reach the model's context?
     *
     * The difference between a candidate and an influence, and the one field that keeps a
     * provenance panel honest.
     */
    @ColumnInfo(name = "supplied_to_model", defaultValue = "0")
    val suppliedToModel: Boolean = false,

    /** A bounded excerpt of what it said. Never the whole document. */
    @ColumnInfo(name = "excerpt", defaultValue = "")
    val excerpt: String = "",

    /** Where it ranked in its lane, when the source ranks. Zero when it does not. */
    @ColumnInfo(name = "rank_in_lane", defaultValue = "0")
    val rankInLane: Int = 0,

    /** The source's own relevance score, when it reports one. */
    @ColumnInfo(name = "score", defaultValue = "0")
    val score: Float = 0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        const val SOURCE_MEMORY = "MEMORY"
        const val SOURCE_NOTE = "NOTE"
        const val SOURCE_PROJECT_DOCUMENT = "PROJECT_DOCUMENT"
        const val SOURCE_CONVERSATION = "CONVERSATION"
        const val SOURCE_TOOL_RESULT = "TOOL_RESULT"
        const val SOURCE_FILE = "FILE"
        const val SOURCE_ARTEFACT = "ARTEFACT"
        const val SOURCE_USER_MESSAGE = "USER_MESSAGE"

        /** How much of a source is quoted. Enough to recognise, bounded so a row stays a row. */
        const val MAX_EXCERPT_CHARS = 1_000
    }
}
