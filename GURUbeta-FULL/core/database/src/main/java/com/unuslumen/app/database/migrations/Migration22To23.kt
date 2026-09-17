package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 22 to 23: Create guru_notes_to_self table.
 *
 * Guru-authored keyword-triggered prompt injections, stored locally. This is
 * the client-side twin of the server's prompt_sections trigger_keywords
 * machinery: Guru writes a note with trigger phrases, and when the user's
 * message matches one, the note content gets injected into the system prompt
 * for that turn.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_notes_to_self (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                content TEXT NOT NULL,
                trigger_keywords TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                source TEXT NOT NULL DEFAULT 'guru',
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                last_fired_at INTEGER NOT NULL DEFAULT 0,
                fire_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_notes_to_self_enabled ON guru_notes_to_self(enabled)")
    }
}