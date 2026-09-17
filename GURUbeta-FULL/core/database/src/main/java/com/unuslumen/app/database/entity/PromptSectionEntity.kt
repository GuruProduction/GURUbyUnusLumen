package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a section of Guru's system prompt.
 * Master content is hardcoded in the app, but sections can be amended.
 */
@Entity(tableName = "prompt_sections")
data class PromptSectionEntity(
    @PrimaryKey
    val id: String,                          // e.g., "identity", "capabilities", "reflection"
    val displayName: String,                  // Human-readable name
    val masterContent: String,                // The hardcoded base content (stored for reference)
    val isEditable: Boolean = true,           // Whether user/Guru can amend this section
    @ColumnInfo(name = "section_order")
    val order: Int                           // Assembly order (lower = earlier in prompt)
)