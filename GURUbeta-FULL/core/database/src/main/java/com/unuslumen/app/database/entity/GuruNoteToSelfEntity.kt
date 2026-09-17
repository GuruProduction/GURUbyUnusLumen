package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * guru_notes_to_self — Guru-authored keyword-triggered prompt injections.
 *
 * The local twin of the server's prompt_sections keyword machinery. When the
 * server's keyword sections are "us in the superadmin steering Guru remotely",
 * these are "Guru steering his own future behaviour on-device". Either half
 * fires the same way: the user's message text matches a keyword/phrase and the
 * note's content gets injected into the system prompt for that turn.
 *
 * Written by Guru through the noteToSelf tool family, stored on-device in
 * guru_db, never synced anywhere. Source marks the authoring path:
 *  - "guru": created by the noteToSelf tool at runtime
 *  - "import": seeded from the legacy import / one-time bulk insert
 */
@Entity(
    tableName = "guru_notes_to_self",
    indices = [
        Index(value = ["enabled"], name = "index_guru_notes_to_self_enabled")
    ]
)
data class GuruNoteToSelfEntity(
    @PrimaryKey
    val id: String,
    /** Human-readable name, what the note is about. */
    val name: String,
    /** The full reminder text injected into context when triggered. */
    val content: String,
    /** Comma-separated keywords/phrases, matched case-insensitively. Empty means never auto-fire. */
    @ColumnInfo(name = "trigger_keywords")
    val triggerKeywords: String,
    /** Whether this note is live. Disabled notes never fire. */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** Where it came from: "guru" or "import". */
    val source: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** Last time this note actually fired, 0 if never. */
    @ColumnInfo(name = "last_fired_at")
    val lastFiredAt: Long = 0,
    /** Total times fired. Diagnostic only. */
    @ColumnInfo(name = "fire_count")
    val fireCount: Int = 0
)