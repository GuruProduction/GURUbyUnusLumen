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
 * A persistent output a run produced.
 *
 * WHY THIS IS NOT JUST A ROW IN ANOTHER TABLE. GURU already has notes, project documents, media and
 * files, and none of them can answer "what did this agent make, in which run, from what sources".
 * An artefact is the link between an output and the work that produced it, and it points at the
 * content rather than copying it: a 400MB video is not going into a ledger.
 *
 * [storageKind] AND [storageRef] ARE THE PAIR THAT MAKES THAT WORK. The kind says which subsystem
 * holds the bytes (a project document, a media item, a file on disk, or a text body small enough to
 * carry inline), and the ref says which one. A reader looks up the subsystem by kind, so adding a
 * new kind later is a new enum value rather than a schema change.
 *
 * [contentText] IS FOR SMALL ARTEFACTS AND IS DELIBERATELY BOUNDED. A report, a JSON result, a
 * snippet of code: things whose whole value is the text. Anything larger belongs in a store, and the
 * write path refuses to inline it rather than filling the row with a megabyte.
 *
 * THE PROVENANCE IS FIVE FACTS, AND ALL FIVE ARE STORED. Producing agent, run, version, creation
 * time and sources. The specification's section 2.7 lists exactly these, and an artefact without
 * them is an orphan file whose origin nobody can establish later.
 */
@Entity(
    tableName = "agent_artefacts",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "created_at"], name = "index_agent_artefacts_run_id_created_at"),
        Index(value = ["agent_id", "created_at"], name = "index_agent_artefacts_agent_id_created_at"),
        Index(value = ["kind"], name = "index_agent_artefacts_kind"),
        Index(value = ["storage_kind", "storage_ref"], name = "index_agent_artefacts_storage"),
    ],
)
data class AgentArtefactEntity(
    @PrimaryKey
    @ColumnInfo(name = "artefact_id")
    val artefactId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    @ColumnInfo(name = "agent_id")
    val agentId: String,

    @ColumnInfo(name = "agent_name")
    val agentName: String,

    /** The agent definition version that produced it, matching the run's snapshot. */
    @ColumnInfo(name = "agent_version", defaultValue = "1")
    val agentVersion: Int = 1,

    @ColumnInfo(name = "title")
    val title: String,

    /** What it is: a report, a dataset, code, an image, a generated tool, a skill. */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "mime_type", defaultValue = "text/plain")
    val mimeType: String = "text/plain",

    /** Where the bytes live. One of the STORAGE_ constants. */
    @ColumnInfo(name = "storage_kind")
    val storageKind: String,

    /** The subsystem's own identifier for the content. */
    @ColumnInfo(name = "storage_ref")
    val storageRef: String,

    /** Small artefacts carried inline. Null for anything held by a store. */
    @ColumnInfo(name = "content_text")
    val contentText: String? = null,

    @ColumnInfo(name = "size_bytes", defaultValue = "0")
    val sizeBytes: Long = 0,

    /**
     * What it was produced from: memory ids, context references, tool results, other artefacts.
     *
     * A JSON array of typed references. Stored as a document because the set of reference kinds
     * will grow and each one is a (kind, id) pair rather than a column.
     */
    @ColumnInfo(name = "sources_json", defaultValue = "[]")
    val sourcesJson: String = "[]",

    /** Intermediate or final. Both are real; the difference matters to a reader deciding what to open. */
    @ColumnInfo(name = "final_output", defaultValue = "1")
    val finalOutput: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        // ---- what it is ----
        const val KIND_REPORT = "REPORT"
        const val KIND_DOCUMENT = "DOCUMENT"
        const val KIND_DATASET = "DATASET"
        const val KIND_CODE = "CODE"
        const val KIND_IMAGE = "IMAGE"
        const val KIND_AUDIO = "AUDIO"
        const val KIND_VIDEO = "VIDEO"
        const val KIND_TOOL = "TOOL"
        const val KIND_SKILL = "SKILL"
        const val KIND_OTHER = "OTHER"

        // ---- where the bytes are ----
        /** Carried inline in [contentText]. Only for small outputs. */
        const val STORAGE_INLINE = "INLINE"

        /** A `project_documents` row. */
        const val STORAGE_PROJECT_DOCUMENT = "PROJECT_DOCUMENT"

        /** A `media_items` row. */
        const val STORAGE_MEDIA_ITEM = "MEDIA_ITEM"

        /** A path on disk, relative to the app's own storage. */
        const val STORAGE_FILE = "FILE"

        /** A brain memory id, when the output is itself a recorded fact. */
        const val STORAGE_MEMORY = "MEMORY"

        /**
         * How large an inline artefact may be.
         *
         * A ROW IS NOT A FILING CABINET. Sixteen kilobytes covers a report, a JSON result or a
         * code snippet, and anything beyond that belongs in a store where it can be streamed and
         * deleted independently. The write path refuses rather than truncating, because a
         * half-stored artefact reads as a complete one.
         */
        const val MAX_INLINE_BYTES = 16 * 1024
    }
}
