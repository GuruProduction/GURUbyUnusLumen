// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Full snapshot of a module's composition at a given revision.
 *
 * Every saveModuleComposition bumps the module's revision and writes one of
 * these rows, which is what makes rollback nearly free: restoring a room is
 * copying the snapshot back onto the module. Compositions persist as history,
 * the same as everything else the numen does.
 */
@Entity(
    tableName = "guru_module_revisions",
    indices = [
        Index(value = ["moduleId"], name = "index_guru_module_revisions_moduleId"),
        Index(value = ["moduleId", "revision"], name = "index_guru_module_revisions_module_revision")
    ]
)
data class GuruModuleRevisionEntity(
    @PrimaryKey
    val id: String,
    val moduleId: String,
    val revision: Int,
    val compositionHtml: String,
    val compositionCss: String,
    val compositionJs: String,
    val dataJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)