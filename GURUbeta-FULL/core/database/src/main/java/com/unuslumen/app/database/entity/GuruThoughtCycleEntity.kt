package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a thought cycle - a background reflection process.
 * Thought cycles allow Guru to autonomously reflect on conversations, memories, and patterns.
 */
@Entity(
    tableName = "guru_thought_cycles",
    indices = [
        Index(value = ["name"], name = "index_guru_thought_cycles_name"),
        Index(value = ["enabled"], name = "index_guru_thought_cycles_enabled"),
        Index(value = ["lastRunAt"], name = "index_guru_thought_cycles_lastRunAt")
    ]
)
data class GuruThoughtCycleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val triggerType: String, // SCHEDULED, EVENT, THRESHOLD
    val triggerConfig: String, // JSON config
    val thoughtProcess: String, // JSON thought process definition
    val outputType: String, // INSIGHT, ACTION, MEMORY, PROPOSAL
    val outputConfig: String?, // JSON output config
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    @ColumnInfo(defaultValue = "0")
    val runCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val insightCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val actionCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val proposalCount: Int = 0
)