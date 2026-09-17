package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 21 to 22: Recreate the tool_results_fts FTS5 virtual table.
 *
 * On some devices the FTS5 table was never created (Migration17To18's try/catch
 * silently dropped it when FTS5 was unavailable) and the onCreate callback in
 * DatabaseModule did not run because the database was already past version 18.
 * This migration recreates the table with IF NOT EXISTS, rebuilds the sync
 * triggers, and backfills from the existing tool_results rows.
 *
 * If FTS5 is not available on the device, the creation is skipped silently.
 * The DAO's caller-side try/catch blocks fall back to LIKE-based search.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop any partial triggers from a failed previous attempt
        db.execSQL("DROP TRIGGER IF EXISTS tool_results_ai")
        db.execSQL("DROP TRIGGER IF EXISTS tool_results_ad")
        db.execSQL("DROP TRIGGER IF EXISTS tool_results_au")

        // Create the FTS5 virtual table if it does not exist
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS tool_results_fts USING fts5(
                    result_text,
                    content='tool_results',
                    content_rowid='rowid'
                )
            """.trimIndent())

            // Recreate sync triggers
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS tool_results_ai AFTER INSERT ON tool_results BEGIN
                    INSERT INTO tool_results_fts(rowid, result_text)
                    VALUES (new.rowid, new.result_text);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS tool_results_ad AFTER DELETE ON tool_results BEGIN
                    INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text)
                    VALUES ('delete', old.rowid, old.result_text);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS tool_results_au AFTER UPDATE ON tool_results BEGIN
                    INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text)
                    VALUES ('delete', old.rowid, old.result_text);
                    INSERT INTO tool_results_fts(rowid, result_text)
                    VALUES (new.rowid, new.result_text);
                END
            """.trimIndent())

            // Backfill existing rows into the FTS index
            db.execSQL("INSERT INTO tool_results_fts(tool_results_fts) VALUES('rebuild')")
        } catch (e: Exception) {
            // FTS5 not available on this device. Clean up any partial creation.
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_ai")
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_ad")
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_au")
            db.execSQL("DROP TABLE IF EXISTS tool_results_fts")
        }
    }
}