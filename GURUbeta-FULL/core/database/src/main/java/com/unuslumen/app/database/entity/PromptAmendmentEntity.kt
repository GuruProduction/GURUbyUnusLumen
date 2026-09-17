package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an amendment to a prompt section.
 * Amendments are proposed by either the user or Guru and require approval before taking effect.
 */
@Entity(
    tableName = "prompt_amendments",
    foreignKeys = [
        ForeignKey(
            entity = PromptSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["section_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("section_id")]
)
data class PromptAmendmentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "section_id")
    val sectionId: String,
    val type: String,                         // "ADD", "MODIFY", "REPLACE"
    val content: String,                      // The amendment content
    val proposedBy: String,                   // "USER" or "GURU"
    val status: String,                       // "PENDING", "APPROVED", "REJECTED", "ROLLED_BACK"
    @ColumnInfo(name = "rationale")
    val rationale: String? = null,            // Why this amendment was proposed
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "approved_at")
    val approvedAt: Long? = null,
    @ColumnInfo(name = "rejected_at")
    val rejectedAt: Long? = null,
    @ColumnInfo(name = "rollback_reason")
    val rollbackReason: String? = null,
    val version: Int = 1                      // For version history
)