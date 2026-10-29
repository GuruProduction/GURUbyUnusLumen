// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 27 to 28: the structured action-and-observation log.
 *
 * One new table, no rebuilds, no data touched. tool_observations is a
 * brand-new concept in v28 — nothing exists to migrate into it, and the first
 * row is written by the first tool call that reports an outcome.
 *
 * Column-for-column identical to ToolObservationEntity because Room validates
 * schema identity on open: a mismatch here crashes the app on first launch
 * after upgrade rather than failing quietly, which is the correct behaviour.
 * Defaults mirror the @ColumnInfo defaults exactly.
 *
 * The composite index on (package_name, timestamp) carries the two reads that
 * matter: "what did tools do to this app recently" and "what failed here last
 * time". Index name matches the Room-generated name so the two never disagree.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tool_observations (
                id TEXT NOT NULL DEFAULT '' PRIMARY KEY,
                package_name TEXT NOT NULL DEFAULT '',
                activity_class TEXT,
                tool_name TEXT NOT NULL DEFAULT '',
                arguments_json TEXT NOT NULL DEFAULT '',
                result_summary TEXT NOT NULL DEFAULT '',
                success INTEGER NOT NULL DEFAULT 1,
                screen_fingerprint TEXT,
                accessibility_tree_json TEXT,
                conversation_id TEXT,
                timestamp INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tool_observations_package_timestamp " +
                "ON tool_observations (package_name, timestamp)"
        )
    }
}
