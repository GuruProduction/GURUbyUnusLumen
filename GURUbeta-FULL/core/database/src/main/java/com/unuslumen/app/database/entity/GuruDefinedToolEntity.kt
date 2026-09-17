package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a tool defined by Guru.
 * Guru can create new tools by composing existing operations.
 * Tools require user approval before becoming active.
 */
@Entity(
    tableName = "guru_defined_tools",
    indices = [
        Index(value = ["name"], name = "index_guru_defined_tools_name"),
        Index(value = ["status"], name = "index_guru_defined_tools_status")
    ]
)
data class GuruDefinedToolEntity(
    @PrimaryKey
    val id: String,
    val name: String,                          // Tool name (e.g., "weatherSummary")
    val displayName: String,                   // Human-readable name
    val description: String,                   // LLM-readable description
    val parameters: String,                    // JSON schema for parameters
    val implementation: String,                // JSON defining implementation (composition, shell, webhook)
    val status: String,                        // "PENDING", "APPROVED", "DISABLED"
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "approved_at")
    val approvedAt: Long? = null,
    @ColumnInfo(name = "created_by")
    val createdBy: String,                     // "guru" or user ID
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
    @ColumnInfo(name = "use_count", defaultValue = "0")
    val useCount: Int = 0,
    @ColumnInfo(name = "rationale")
    val rationale: String? = null              // Why Guru created this tool
)