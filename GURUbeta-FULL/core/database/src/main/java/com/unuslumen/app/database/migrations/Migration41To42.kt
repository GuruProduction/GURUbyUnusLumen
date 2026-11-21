// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 41 to 42: the prompt stack becomes a real local store.
 *
 * WHY THIS IS 41→42 AND NOT 36→37. This work was written when the database was at 36, and by the
 * time it landed the agent model had already taken 36→37 and shipped through 41. Reusing that hop
 * would have meant two different upgrades claiming one version number, and a device on 41 would
 * never run either of them. The version it upgrades FROM is the version the device is actually on.
 *
 * WHAT WAS WRONG. `prompt_sections` was created with five columns, populated by nothing, read by
 * nothing, and had a sibling `prompt_amendments` table that pointed at it with a foreign key and was
 * also never written. Meanwhile the app received one assembled string from the server. So the device
 * held a table whose only possible answer to "what does my prompt say" was silence, and silence reads
 * as "the stack is empty".
 *
 * So both dead tables go and two real ones replace them: `prompt_rows`, mirroring the server's
 * prompt sections column for column plus the two the device needs, and `prompt_row_versions`,
 * mirroring the server's version table.
 *
 * DROPPING IS SAFE HERE AND THAT IS A MEASURED FACT, NOT A HOPE. Neither table had a writer: no
 * code path inserted a row, so both are empty on every device that ever ran this app. A user
 * upgrading loses two empty tables.
 *
 * THE OLD SHAPE IS NOT REUSED FOR THE NEW TABLE. Reusing the name would leave an upgraded install
 * with five columns and a fresh install with sixteen, and Room validates the schema on open, so the
 * two would not be the same database.
 *
 * COLUMN NAMES, TYPES, NULLABILITY, DEFAULTS AND ORDER come from the entity definitions, because
 * Room compares them exactly and a mismatch crashes the first launch after an upgrade rather than
 * failing quietly.
 */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The dead pair, in child-then-parent order because of the foreign key.
        db.execSQL("DROP TABLE IF EXISTS `prompt_amendments`")
        db.execSQL("DROP TABLE IF EXISTS `prompt_sections`")

        // ---------------- prompt_rows ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_rows` (
                `id` TEXT NOT NULL,
                `slug` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `delivery_mode` TEXT NOT NULL DEFAULT 'always',
                `trigger_keywords` TEXT NOT NULL DEFAULT '[]',
                `match_threshold` REAL,
                `global_threshold` REAL NOT NULL DEFAULT 0.75,
                `is_active` INTEGER NOT NULL DEFAULT 1,
                `version` INTEGER NOT NULL DEFAULT 1,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `is_edited` INTEGER NOT NULL DEFAULT 0,
                `synced_version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_prompt_rows_slug` ON `prompt_rows` (`slug`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_rows_category` ON `prompt_rows` (`category`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_prompt_rows_is_active` ON `prompt_rows` (`is_active`)"
        )

        // ---------------- prompt_row_versions ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prompt_row_versions` (
                `id` TEXT NOT NULL,
                `row_id` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `written_by` TEXT NOT NULL DEFAULT 'server',
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`row_id`) REFERENCES `prompt_rows`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_prompt_row_versions_row_id_version` " +
                "ON `prompt_row_versions` (`row_id`, `version`)"
        )
    }
}
