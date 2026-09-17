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