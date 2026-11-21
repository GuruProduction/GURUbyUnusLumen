// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 37 to 38: the message model and the approval record.
 *
 * THE GAP THIS CLOSES. An agent's conversation with GURU existed only as rows in an in-memory map,
 * and its authorship was implied by position in a list: a question and an answer were two adjacent
 * entries and nothing said which was which or who wrote it. The specification requires explicit
 * author, recipient, type, sequence, correlation and delivery state, and none of that can be
 * inferred from a list position. Separately, an approval requirement had nowhere to be recorded, so
 * "may I do this" could only ever be prose in a chat.
 *
 * PURELY ADDITIVE. Two new tables, no existing row read, rewritten or dropped. An upgrading install
 * keeps every agent, run, event, checkpoint, conversation and tool record.
 *
 * WHY MESSAGES ARE A TABLE AND NOT EVENTS. A conversation must be read in order as a conversation,
 * filtered by participant, and correlated into reply chains. The event ledger is append-only and
 * ordered by what happened, which is the right shape for evidence and the wrong shape for a
 * timeline that gets recomputed every time somebody opens a room.
 *
 * THE FINGERPRINT INDEX ON APPROVALS IS NOT UNIQUE, DELIBERATELY. The same action may legitimately
 * be asked about twice in one run — a retry after an unrelated failure — and each request is its own
 * record with its own decision. What the fingerprint prevents is an approval being *spent* on a
 * different action, and that check lives in the runtime, not in a uniqueness constraint.
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- agent_messages ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_messages` (
                `message_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `sequence_number` INTEGER NOT NULL,
                `author_type` TEXT NOT NULL,
                `author_id` TEXT,
                `recipient_type` TEXT NOT NULL,
                `recipient_id` TEXT,
                `message_type` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `correlation_id` TEXT,
                `delivery_state` TEXT NOT NULL DEFAULT 'QUEUED',
                `delivery_failure_reason` TEXT,
                `urgency` TEXT NOT NULL DEFAULT 'NORMAL',
                `created_at` INTEGER NOT NULL,
                `delivered_at` INTEGER,
                `consumed_at` INTEGER,
                PRIMARY KEY(`message_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_messages_run_id_sequence` " +
                "ON `agent_messages` (`run_id`, `sequence_number`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_messages_run_id_created_at` " +
                "ON `agent_messages` (`run_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_messages_run_id_author` " +
                "ON `agent_messages` (`run_id`, `author_type`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_messages_correlation_id` " +
                "ON `agent_messages` (`correlation_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_messages_delivery_state` " +
                "ON `agent_messages` (`delivery_state`)"
        )

        // ---------------- agent_approvals ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_approvals` (
                `approval_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `what` TEXT NOT NULL,
                `why` TEXT NOT NULL,
                `affected` TEXT NOT NULL,
                `reversible` INTEGER NOT NULL DEFAULT 0,
                `consequences` TEXT NOT NULL,
                `requested_permission` TEXT NOT NULL,
                `action_fingerprint` TEXT NOT NULL,
                `state` TEXT NOT NULL DEFAULT 'PENDING',
                `decided_by` TEXT,
                `decided_at` INTEGER,
                `decision_note` TEXT,
                `requested_at` INTEGER NOT NULL,
                PRIMARY KEY(`approval_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_approvals_run_id_state` " +
                "ON `agent_approvals` (`run_id`, `state`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_approvals_state_requested_at` " +
                "ON `agent_approvals` (`state`, `requested_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_approvals_fingerprint` " +
                "ON `agent_approvals` (`action_fingerprint`)"
        )
    }
}
