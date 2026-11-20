// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 40 to 41: a local restriction on an imported agent's tools.
 *
 * THE GAP THIS CLOSES. A catalogue agent arrived with exactly the tools it shipped with, which is
 * right, and there was no way to hold it to fewer. Section 13 asks for isolation of externally
 * sourced agents, and a person who wants an imported agent for one job should not have to accept its
 * whole published grant to get it.
 *
 * ONE ADDITIVE COLUMN, DEFAULTING TO EMPTY. Empty means no restriction, which is what every agent on
 * every existing installation has, so nothing about an installed agent changes.
 *
 * WHY THIS IS NOT A REWRITE OF `tool_permissions`. The published grant is what a later version is
 * diffed against, and that diff is what stops an update smuggling in a new permission. Holding an
 * agent to fewer tools by editing the stored grant would erase the comparison, so the person's choice
 * lives in its own column and the effective set is the published list filtered by it.
 */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `guru_agents` ADD COLUMN `tool_narrowing` TEXT NOT NULL DEFAULT ''"
        )
    }
}
