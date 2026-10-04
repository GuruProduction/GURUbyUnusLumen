// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "luxify_skills",
    indices = [
        Index(value = ["name"], name = "index_luxify_skills_name"),
        Index(value = ["enabled"], name = "index_luxify_skills_enabled"),
        Index(value = ["source"], name = "index_luxify_skills_source")
    ]
)
data class LuxifyEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "when_to_use")
    val whenToUse: String,
    @ColumnInfo(name = "allowed_tools")
    val allowedTools: String,
    @ColumnInfo(name = "body_markdown")
    val bodyMarkdown: String,
    val source: String,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)