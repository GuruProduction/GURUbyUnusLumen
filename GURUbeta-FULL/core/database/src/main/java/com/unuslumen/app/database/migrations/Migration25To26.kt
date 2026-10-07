// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 25 to 26: guru_modules status hygiene.
 *
 * The v25 CREATE TABLE default for status is 'draft' lowercase, while the
 * domain enum and every explicit write path speak UPPERCASE. The write path
 * was fixed to set status explicitly, leaving the literal dormant but
 * poisoned for any future insert that omits status. This migration rebuilds
 * the table with a correct default and sweeps any lowercase values already
 * stored.
 *
 * Rebuild follows the canonical SQLite rename-copy-drop pattern: new table
 * with the corrected schema, copy existing rows normalising status casing,
 * drop the old, rename. Indexes drop with their parent table and are
 * recreated against the rebuilt table.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_modules_v26 (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                category TEXT NOT NULL,
                compositionHtml TEXT NOT NULL,
                compositionCss TEXT NOT NULL,
                compositionJs TEXT NOT NULL,
                dataJson TEXT NOT NULL,
                iconPath TEXT,
                revision INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'DRAFT',
                sort_order INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        // Normalise every existing row's casing on the way through, so the
        // domain enum is the single source of truth from this point on.
        db.execSQL("""
            INSERT INTO guru_modules_v26 (
                id, name, displayName, description, category,
                compositionHtml, compositionCss, compositionJs, dataJson,
                iconPath, revision, status, sort_order, source, created_at, updated_at
            )
            SELECT
                id, name, displayName, description, category,
                compositionHtml, compositionCss, compositionJs, dataJson,
                iconPath, revision, UPPER(status), sort_order, source, created_at, updated_at
            FROM guru_modules
        """.trimIndent())

        db.execSQL("DROP TABLE guru_modules")
        db.execSQL("ALTER TABLE guru_modules_v26 RENAME TO guru_modules")

        // Recreate the three indexes the rebuild dropped with the parent.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_name ON guru_modules (name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_status ON guru_modules (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_sort_order ON guru_modules (sort_order)")

        // Safety sweep: any straggler case variants the copy missed via the
        // SELECT path (there are none, but the sweep costs nothing and a
        // future default change must never reintroduce the disease).
        db.execSQL("UPDATE guru_modules SET status = UPPER(status) WHERE status != UPPER(status)")
    }
}