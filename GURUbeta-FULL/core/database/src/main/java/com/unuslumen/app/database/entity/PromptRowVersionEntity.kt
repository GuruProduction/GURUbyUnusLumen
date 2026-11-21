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
 * One version of one prompt row: the local mirror of the server's `prompt_versions`.
 *
 * WHY EVERY VERSION IS KEPT RATHER THAN ONLY THE LAST. A prompt is the text that decides
 * how GURU behaves, and the change that made it worse is only findable if the text from
 * before still exists. The server keeps the whole chain; this keeps the same chain
 * locally, so an edit made on a phone with no connection is still recoverable after the
 * thing it replaced has been overwritten.
 *
 * THE WRITE RULE, TAKEN FROM THE SERVER RATHER THAN INVENTED: before a row is updated, its
 * current text is inserted here under its current version, and then the row's new text
 * goes in under the next version. Both happen in one transaction with a version guard, so
 * a save built on a version that has since moved on is refused rather than applied over
 * newer words.
 *
 * [rowId] POINTS AT THE ROW'S OWN ID, NOT ITS SLUG. A slug can be renamed and the history
 * has to follow the row, not the handle it used to answer to.
 */
@Entity(
    tableName = "prompt_row_versions",
    foreignKeys = [
        ForeignKey(
            entity = PromptRowEntity::class,
            parentColumns = ["id"],
            childColumns = ["row_id"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [
        Index(
            value = ["row_id", "version"],
            name = "index_prompt_row_versions_row_id_version",
            unique = true
        ),
    ],
)
data class PromptRowVersionEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "row_id")
    val rowId: String,

    /** The version this text was in force as. */
    val version: Int,

    /** The name at that version, because a rename is part of what changed. */
    val name: String,

    val content: String,

    /** Who wrote this version: `server` for a sync, `device` for a local edit. */
    @ColumnInfo(name = "written_by", defaultValue = "server")
    val writtenBy: String = WRITTEN_BY_SERVER,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        const val WRITTEN_BY_SERVER = "server"
        const val WRITTEN_BY_DEVICE = "device"
        const val WRITTEN_BY_RESET = "reset"
    }
}
