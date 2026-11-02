// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 32 to 33: the heartbeat prompt becomes editable.
 *
 * THE GAP THIS CLOSES. The prompt a heartbeat beats with shipped as a compiled
 * asset, so what GURU says to itself every half hour could only be changed by
 * shipping a new build. It is the owner's own agent and the owner's own cadence, so
 * the text belongs to them and belongs in the database where the Heartbeat room can
 * edit it.
 *
 * ONE NEW TABLE, purely additive. No existing row is touched, so nothing that
 * already works can be affected. Column names, types, nullability and defaults are
 * taken from HeartbeatPromptEntity rather than written from memory, because Room
 * validates schema identity on open and a mismatch would crash on the first launch
 * after upgrade.
 *
 * THE TABLE IS CREATED EMPTY ON PURPOSE. No row means "the bundled text is in force",
 * which is exactly what an upgrading install should do: it keeps beating with the
 * prompt it already had until somebody saves a new one.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `heartbeat_prompt` (
                `id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}
