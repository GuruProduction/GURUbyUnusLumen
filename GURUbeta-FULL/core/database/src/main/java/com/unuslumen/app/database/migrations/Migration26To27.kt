// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 26 to 27: the onboarding profile row.
 *
 * One new table, no rebuilds, no data touched. The onboarding bootstrap is a
 * brand-new concept in v27, so nothing exists to migrate into it; the row is
 * created by the first save and never before.
 *
 * Column-for-column identical to UserProfileEntity because Room validates
 * schema identity on open: a mismatch here crashes the app on first launch
 * after upgrade rather than failing quietly, which is the correct behaviour.
 * Defaults mirror the @ColumnInfo defaults exactly.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_user_profile (
                id TEXT NOT NULL PRIMARY KEY,
                preferred_name TEXT NOT NULL DEFAULT '',
                full_name TEXT NOT NULL DEFAULT '',
                pronouns TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                age INTEGER,
                identity TEXT NOT NULL DEFAULT '',
                neurodivergence TEXT NOT NULL DEFAULT '',
                beliefs TEXT NOT NULL DEFAULT '',
                sexuality TEXT NOT NULL DEFAULT '',
                politics TEXT NOT NULL DEFAULT '',
                family TEXT NOT NULL DEFAULT '',
                work TEXT NOT NULL DEFAULT '',
                current_focus TEXT NOT NULL DEFAULT '',
                adjustments TEXT NOT NULL DEFAULT '',
                health_or_legal TEXT NOT NULL DEFAULT '',
                interests TEXT NOT NULL DEFAULT '',
                news_sources TEXT NOT NULL DEFAULT '',
                socials TEXT NOT NULL DEFAULT '',
                never_ask_again TEXT NOT NULL DEFAULT '',
                extra_answers_json TEXT NOT NULL DEFAULT '{}',
                facts_by_id_json TEXT NOT NULL DEFAULT '{}',
                pending_brain_sync_json TEXT NOT NULL DEFAULT '[]',
                deal_accepted_at INTEGER NOT NULL DEFAULT 0,
                deal_version INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}
