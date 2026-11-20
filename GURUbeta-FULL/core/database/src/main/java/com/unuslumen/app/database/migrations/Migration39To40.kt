// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 39 to 40: agent schedules.
 *
 * THE GAP THIS CLOSES. `AgentAutonomy.operateOnSchedules` has been declared since Phase 2 and nothing
 * has ever read it, because there was nothing to schedule. An agent could only work when somebody
 * asked it to, which makes a whole class of work impossible: the daily sweep, the periodic check, the
 * thing that should happen every morning whether or not anyone remembers.
 *
 * ONE NEW TABLE, PURELY ADDITIVE. No existing row is read, rewritten or dropped, and an installation
 * with no schedules behaves exactly as it did before.
 *
 * WHY NOT REUSE THE HEARTBEAT TABLES. The heartbeat fires a prompt at the assistant on a fixed
 * cadence. It has no notion of which agent, which job, or whether that agent is permitted to run
 * unattended, and giving it one would make a single alarm mean two different things depending on
 * state stored elsewhere. This table names its agent and its job explicitly, so what a schedule will
 * do is readable from the schedule.
 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_schedules` (
                `schedule_id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `agent_slug` TEXT NOT NULL DEFAULT '',
                `title` TEXT NOT NULL,
                `assignment` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `interval_millis` INTEGER NOT NULL,
                `next_due_at` INTEGER,
                `last_due_at` INTEGER,
                `last_started_at` INTEGER,
                `last_run_id` TEXT,
                `conversation_id` TEXT,
                `missed_count` INTEGER NOT NULL DEFAULT 0,
                `last_failure` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`schedule_id`)
            )
            """.trimIndent()
        )

        /*
         * TWO INDEXES, FOR TWO DIFFERENT QUESTIONS.
         *
         * The first answers "what will start this agent on its own", which the room asks. The second
         * answers "what is due now", which the runner asks every time it wakes, and it is the one on
         * the hot path: without it every wake scans every schedule.
         */
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_schedules_agent_id` " +
                "ON `agent_schedules` (`agent_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_schedules_enabled_next_due` " +
                "ON `agent_schedules` (`enabled`, `next_due_at`)"
        )
    }
}
