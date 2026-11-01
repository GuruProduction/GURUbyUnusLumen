// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 31 to 32: the agent definitions table. Issue: in-app agent creator.
 *
 * THE GAP THIS CLOSES. Nothing on the phone could store an agent definition, so
 * the four hardcoded names in GuruAgentSelector and GuruMaskSelector were the
 * whole of the agent story and neither was ever rendered. This adds one table so
 * a person can make an agent, the numen can call it by name, and a reviewed
 * catalogue agent can be downloaded and kept.
 *
 * ONE NEW TABLE, purely additive. No existing row is touched, so this migration
 * cannot corrupt anything that already works. Column names, types, nullability
 * and defaults are identical to GuruAgentEntity because Room validates schema
 * identity on open, and a mismatch crashes on the first launch after upgrade
 * rather than failing quietly.
 *
 * Room's generated schema expects the exact column order and defaults, so these
 * statements were taken from the entity definition rather than written from
 * memory.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `guru_agents` (
                `id` TEXT NOT NULL,
                `slug` TEXT NOT NULL DEFAULT '',
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `system_prompt` TEXT NOT NULL,
                `tool_permissions` TEXT NOT NULL DEFAULT '',
                `source` TEXT NOT NULL DEFAULT 'user',
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `version` INTEGER NOT NULL DEFAULT 1,
                `created_at` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guru_agents_name` ON `guru_agents` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guru_agents_slug` ON `guru_agents` (`slug`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guru_agents_source` ON `guru_agents` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guru_agents_enabled` ON `guru_agents` (`enabled`)")
    }
}
