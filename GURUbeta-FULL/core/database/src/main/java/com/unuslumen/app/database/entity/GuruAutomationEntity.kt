package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "guru_automations",
    indices = [
        Index(value = ["name"], name = "index_guru_automations_name"),
        Index(value = ["trigger"], name = "index_guru_automations_trigger"),
        Index(value = ["enabled"], name = "index_guru_automations_enabled")
    ]
)
data class GuruAutomationEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val trigger: String,
    val triggerConfig: String,
    val steps: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_run_at")
    val lastRunAt: Long? = null,
    @ColumnInfo(name = "run_count", defaultValue = "0")
    val runCount: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true
)