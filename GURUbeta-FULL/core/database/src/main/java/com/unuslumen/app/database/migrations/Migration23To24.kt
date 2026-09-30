package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 23 to 24: create the media library.
 *
 * media_items holds one row per ingested video / audio / image. The schema's
 * derived plain-text twins (transcript_text, ocr_text) exist on the row so the
 * FTS5 external-content virtual table media_items_fts can index REAL columns,
 * the exact established app-wide pattern (tool_results_fts over result_text,
 * messages_fts over messages.content). Derived columns are written at ingest
 * time and their sync triggers keep the FTS index fresh without a writer
 * needing to know it exists.
 *
 * media_zoom_log stores every zoom with its frame path.
 *
 * The FTS rebuild backfills the index after everything is created, matching
 * Migration13_14's pattern.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Media items, every Room-schema column and index verbatim.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_items (
                id TEXT NOT NULL PRIMARY KEY,
                source_filename TEXT NOT NULL,
                cached_path TEXT NOT NULL,
                stored_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                media_kind TEXT NOT NULL,
                duration_seconds REAL NOT NULL DEFAULT 0.0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                fps REAL NOT NULL DEFAULT 0.0,
                has_audio INTEGER NOT NULL DEFAULT 0,
                transcript_json TEXT NOT NULL DEFAULT '',
                scenes_json TEXT NOT NULL DEFAULT '',
                transcript_text TEXT NOT NULL DEFAULT '',
                ocr_text TEXT NOT NULL DEFAULT '',
                poster_thumb_path TEXT NOT NULL DEFAULT '',
                ingest_status TEXT NOT NULL DEFAULT 'PROCESSING',
                error_message TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_media_items_sha256 ON media_items (sha256)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_items_created ON media_items (created_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_items_kind ON media_items (media_kind)"
        )

        // 2. Zoom log.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_zoom_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                media_id TEXT NOT NULL,
                timestamp_sec REAL NOT NULL,
                extracted_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_zoom_log_media_id ON media_zoom_log (media_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_zoom_log_created_at ON media_zoom_log (created_at)"
        )

        // 3. External-content FTS5 over the real derived columns.
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS media_items_fts USING fts5(
                    transcript_text,
                    ocr_text,
                    source_filename,
                    content='media_items',
                    content_rowid='rowid'
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS media_items_ai AFTER INSERT ON media_items BEGIN
                    INSERT INTO media_items_fts(rowid, transcript_text, ocr_text, source_filename)
                    VALUES (new.rowid, new.transcript_text, new.ocr_text, new.source_filename);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS media_items_ad AFTER DELETE ON media_items BEGIN
                    INSERT INTO media_items_fts(media_items_fts, rowid, transcript_text, ocr_text, source_filename)
                    VALUES ('delete', old.rowid, old.transcript_text, old.ocr_text, old.source_filename);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS media_items_au AFTER UPDATE ON media_items BEGIN
                    INSERT INTO media_items_fts(media_items_fts, rowid, transcript_text, ocr_text, source_filename)
                    VALUES ('delete', old.rowid, old.transcript_text, old.ocr_text, old.source_filename);
                    INSERT INTO media_items_fts(rowid, transcript_text, ocr_text, source_filename)
                    VALUES (new.rowid, new.transcript_text, new.ocr_text, new.source_filename);
                END
            """.trimIndent())

            // 4. Rebuild the index fresh (backfills all current media_items rows).
            db.execSQL("INSERT INTO media_items_fts(media_items_fts) VALUES('rebuild')")
        } catch (e: Exception) {
            android.util.Log.w("guru_migration", "media_items_fts creation failed: ${e.message}")
        }
    }
}