// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved version of an agent's definition.
 *
 * THE GAP THIS CLOSES. `updateAgent` overwrote the row in place. Edit an agent's instructions and
 * the previous instructions ceased to exist: no history, no diff, no way to answer "what was this
 * agent told last week when it did that job". The specification requires that historical
 * definitions remain interpretable after later updates, and a single mutable row cannot provide
 * that at any price.
 *
 * ONE ROW PER SAVE, NOT ONE ROW PER FIELD. The definition is only coherent as a whole: a prompt
 * that expects a tool it no longer has is a different agent, not a patch. Storing the complete
 * document means a version can be restored exactly rather than reconstructed by replaying
 * per-field changes, which is the same reason a run snapshots values rather than naming a version.
 *
 * NO FOREIGN KEY TO guru_agents. An agent can be deleted, and its history is evidence of what it
 * was. A foreign key with cascade would delete the evidence with the thing it describes, and one
 * with restrict would make deleting an agent impossible. The identity is carried as a value, the
 * same decision as `agent_runs.agent_id`.
 *
 * SEALED AT REST. The system prompt is the agent's instructions and may contain anything its owner
 * wrote, so it is written through the same protector the ledger uses rather than in the clear.
 */
@Entity(
    tableName = "agent_definition_versions",
    indices = [
        Index(
            value = ["agent_id", "definition_version"],
            name = "index_agent_definition_versions_agent_id_version",
            unique = true,
        ),
        Index(value = ["agent_id", "saved_at"], name = "index_agent_definition_versions_agent_id_saved_at"),
        Index(value = ["slug"], name = "index_agent_definition_versions_slug"),
    ],
)
data class AgentDefinitionVersionEntity(
    @PrimaryKey
    @ColumnInfo(name = "version_row_id")
    val versionRowId: String,

    /** The agent this definition belongs to. A value, not a foreign key: see the class comment. */
    @ColumnInfo(name = "agent_id")
    val agentId: String,

    @ColumnInfo(name = "slug", defaultValue = "")
    val slug: String = "",

    @ColumnInfo(name = "name")
    val name: String,

    /** One-based, matching the `definitionVersion` the live row carried after this save. */
    @ColumnInfo(name = "definition_version")
    val definitionVersion: Int,

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    /** The complete definition document as JSON, sealed. Restorable exactly. */
    @ColumnInfo(name = "definition_json")
    val definitionJson: String,

    /** Why this version was written: created, edited here, or arrived from the catalogue. */
    @ColumnInfo(name = "reason")
    val reason: String,

    /** Who or what wrote it. Uses the entity actor constants rather than a bare literal. */
    @ColumnInfo(name = "author")
    val author: String,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long,
) {
    companion object {
        const val REASON_CREATED = "CREATED"
        const val REASON_EDITED = "EDITED"
        const val REASON_SYNCED = "SYNCED"
        const val REASON_RESTORED = "RESTORED"

        const val AUTHOR_USER = AgentRunEventEntity.ACTOR_USER
        const val AUTHOR_GURU = AgentRunEventEntity.ACTOR_GURU
        const val AUTHOR_CATALOGUE = AgentRunEventEntity.ACTOR_SYSTEM
    }
}
