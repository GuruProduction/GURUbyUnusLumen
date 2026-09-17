package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 16 to 17: Create FTS5 full-text search virtual tables for messages and memory_facts.
 *
 * Requires sqlite-framework (Android OS SQLite) which includes the FTS5 module.
 * If the SQLite library does not support FTS5, the migration catches the error
 * and completes successfully without FTS. The search DAOs have LIKE-based fallbacks
 * that work without FTS, so the app continues to function.
 *
 * The migration is idempotent: it drops any partial FTS tables and triggers before
 * recreating them, so it's safe to re-run if a previous attempt failed partway.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- messages_fts ---
        createFtsTable(
            db = db,
            ftsTableName = "messages_fts",
            sourceTableName = "messages",
            triggerSuffix = "messages",
            columns = listOf("content", "role"),
            columnMappings = mapOf("content" to "content", "role" to "role")
        )

        // --- memory_facts_fts ---
        createFtsTable(
            db = db,
            ftsTableName = "memory_facts_fts",
            sourceTableName = "memory_facts",
            triggerSuffix = "memory_facts",
            columns = listOf("fact", "category"),
            columnMappings = mapOf("fact" to "fact", "category" to "category")
        )
    }

    private fun createFtsTable(
        db: SupportSQLiteDatabase,
        ftsTableName: String,
        sourceTableName: String,
        triggerSuffix: String,
        columns: List<String>,
        columnMappings: Map<String, String>
    ) {
        // Clean up any remnants from failed previous attempts
        db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_ai")
        db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_ad")
        db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_au")
        db.execSQL("DROP TABLE IF EXISTS $ftsTableName")

        // Try to create the FTS5 table. If the SQLite module isn't available, skip.
        try {
            val columnDefs = columns.joinToString(",\n                ")
            db.execSQL("""
                CREATE VIRTUAL TABLE $ftsTableName USING fts5(
                    $columnDefs,
                    content='$sourceTableName',
                    content_rowid='rowid'
                )
            """.trimIndent())

            val insertCols = columns.joinToString(", ")
            val newCols = columns.joinToString(", ") { "new.$it" }
            val oldCols = columns.joinToString(", ") { "old.$it" }

            db.execSQL("""
                CREATE TRIGGER ${triggerSuffix}_ai AFTER INSERT ON $sourceTableName BEGIN
                    INSERT INTO $ftsTableName(rowid, $insertCols)
                    VALUES (new.rowid, $newCols);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER ${triggerSuffix}_ad AFTER DELETE ON $sourceTableName BEGIN
                    INSERT INTO $ftsTableName($ftsTableName, rowid, $insertCols)
                    VALUES ('delete', old.rowid, $oldCols);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER ${triggerSuffix}_au AFTER UPDATE ON $sourceTableName BEGIN
                    INSERT INTO $ftsTableName($ftsTableName, rowid, $insertCols)
                    VALUES ('delete', old.rowid, $oldCols);
                    INSERT INTO $ftsTableName(rowid, $insertCols)
                    VALUES (new.rowid, $newCols);
                END
            """.trimIndent())

            // Populate FTS from existing data
            db.execSQL("INSERT INTO $ftsTableName($ftsTableName) VALUES('rebuild')")
        } catch (e: Exception) {
            // FTS5 not available in this SQLite build. Clean up any partial creation.
            db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_ai")
            db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_ad")
            db.execSQL("DROP TRIGGER IF EXISTS ${triggerSuffix}_au")
            db.execSQL("DROP TABLE IF EXISTS $ftsTableName")
            // Migration completes successfully. FTS-based search will return empty results,
            // but LIKE-based search and DVM search will still work as fallbacks.
        }
    }
}