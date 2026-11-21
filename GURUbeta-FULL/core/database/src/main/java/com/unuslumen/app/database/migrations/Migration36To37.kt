// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 36 to 37: the agent definition and configuration model.
 *
 * THE GAP THIS CLOSES. `guru_agents` held eleven fields: a name, a prompt, a flat tool list and a
 * version. Everything the specification calls configuration was absent, so an agent could not say
 * how much of its work to show, what it may do without asking, which model it wants, how long it
 * may run, or what it needs in order to work at all. Its history was a single mutable row, so
 * editing the prompt destroyed the previous one.
 *
 * PURELY ADDITIVE, LIKE EVERY MIGRATION BEFORE IT. Eleven new columns on `guru_agents` and one new
 * table. No existing row is read, rewritten, dropped or backfilled. An upgrading install keeps
 * every agent, run, conversation, message, tool record and heartbeat entry it had, and each agent
 * loads with the defaults that reproduce exactly what it did before.
 *
 * WHY THE NEW COLUMNS HAVE REAL DEFAULTS RATHER THAN NULL. These documents are decoded into
 * non-null domain types, so a NULL would have to be handled at every read site and one missed site
 * is a crash on an upgraded device. `{"schemaVersion":1}` decodes to the defaults and states which
 * schema the empty document belongs to, which is what lets a later build read an old row without
 * guessing at its shape.
 *
 * COLUMN NAMES, TYPES, NULLABILITY, DEFAULTS AND ORDER COME FROM THE ENTITY DEFINITIONS, because
 * Room validates schema identity when it opens the database and a mismatch crashes the first
 * launch after an upgrade. Order matters as much as names.
 *
 * `ALTER TABLE ADD COLUMN` WITH A CONSTANT DEFAULT DOES NOT REWRITE THE TABLE. SQLite stores the
 * default in the schema and applies it on read, so a table with many rows costs no rewrite and no
 * measurable time on upgrade.
 *
 * THE NEW TABLE HAS NO FOREIGN KEY, deliberately. An agent can be deleted and its definition
 * history is evidence of what it was; a cascade would take the evidence with the subject and a
 * restrict would make the delete impossible. The agent id is carried as a value, exactly as
 * `agent_runs.agent_id` does.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- configuration on guru_agents ----------------

        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `visual_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `visibility_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `autonomy_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `budgets_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `model_preference_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `notifications_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `memory_scope_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `context_scope_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `manifest_json` TEXT NOT NULL " +
                "DEFAULT '{\"schemaVersion\":1}'"
        )
        db.execSQL("ALTER TABLE `guru_agents` ADD COLUMN `skill_ids` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `definition_version` INTEGER NOT NULL DEFAULT 1"
        )

        // ---------------- agent_definition_versions ----------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_definition_versions` (
                `version_row_id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `slug` TEXT NOT NULL DEFAULT '',
                `name` TEXT NOT NULL,
                `definition_version` INTEGER NOT NULL,
                `description` TEXT NOT NULL DEFAULT '',
                `definition_json` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `saved_at` INTEGER NOT NULL,
                PRIMARY KEY(`version_row_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_agent_definition_versions_agent_id_version` " +
                "ON `agent_definition_versions` (`agent_id`, `definition_version`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_definition_versions_agent_id_saved_at` " +
                "ON `agent_definition_versions` (`agent_id`, `saved_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_definition_versions_slug` " +
                "ON `agent_definition_versions` (`slug`)"
        )
    }
}
