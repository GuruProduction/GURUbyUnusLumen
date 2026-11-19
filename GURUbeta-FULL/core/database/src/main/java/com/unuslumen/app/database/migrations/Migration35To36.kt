// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 35 to 36: the agent execution foundation.
 *
 * THE GAP THIS CLOSES. An agent run today exists only as an AgentRuntime.Session in a
 * LinkedHashMap that dies with the process. Nothing records that the run happened,
 * which agent ran it, what it was asked, which tools it called, what came back, or why
 * it stopped. This adds the durable run, the append-only event ledger and the recovery
 * checkpoints, and links the two existing tool tables to them.
 *
 * PURELY ADDITIVE, LIKE EVERY MIGRATION BEFORE IT. Three new tables, four nullable
 * columns, six indexes. No existing row is read, rewritten or dropped, so an upgrading
 * install keeps its agents, conversations, messages, tool history and heartbeat diary.
 * The four new columns default to NULL, which is the honest value for every historical
 * tool call: it predates durable runs and had no run to belong to.
 *
 * COLUMN NAMES, TYPES, NULLABILITY, DEFAULTS AND ORDER ARE TAKEN FROM THE ENTITY
 * DEFINITIONS rather than written from memory, because Room validates schema identity
 * on open and a mismatch crashes the first launch after an upgrade instead of failing
 * quietly. The order matters as much as the names: a column in a different position is
 * a mismatch even when everything else about it is identical.
 *
 * THE FOUR JSON COLUMNS DEFAULT TO A VERSIONED ENVELOPE, not to '{}'. A bare empty
 * object claims to be a document of unknown shape; {"schemaVersion":1} says which shape
 * it is empty of, and that is what lets a later build read an old row without guessing.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- agent_runs ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_runs` (
                `run_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `origin_message_id` TEXT,
                `parent_run_id` TEXT,
                `agent_id` TEXT NOT NULL,
                `agent_slug` TEXT NOT NULL DEFAULT '',
                `agent_name` TEXT NOT NULL,
                `agent_version` INTEGER NOT NULL DEFAULT 1,
                `assignment` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `agent_definition_snapshot_json` TEXT NOT NULL,
                `requested_config_snapshot_json` TEXT NOT NULL DEFAULT '{"schemaVersion":1}',
                `effective_config_snapshot_json` TEXT NOT NULL DEFAULT '{"schemaVersion":1}',
                `resolved_system_prompt` TEXT NOT NULL,
                `tool_manifest_snapshot_json` TEXT NOT NULL,
                `model_provider` TEXT NOT NULL,
                `model_name` TEXT NOT NULL,
                `model_config_snapshot_json` TEXT NOT NULL DEFAULT '{"schemaVersion":1}',
                `reasoning_capture_enabled_at_start` INTEGER NOT NULL DEFAULT 1,
                `reasoning_visibility_snapshot_json` TEXT NOT NULL DEFAULT '{"schemaVersion":1}',
                `last_event_sequence` INTEGER NOT NULL DEFAULT 0,
                `last_checkpoint_sequence` INTEGER NOT NULL DEFAULT 0,
                `lease_owner` TEXT,
                `lease_heartbeat_at` INTEGER,
                `created_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `updated_at` INTEGER NOT NULL,
                `finished_at` INTEGER,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `result_summary` TEXT,
                PRIMARY KEY(`run_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`origin_message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`parent_run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_conversation_id_created_at` " +
                "ON `agent_runs` (`conversation_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_status_updated_at` " +
                "ON `agent_runs` (`status`, `updated_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_status_lease_heartbeat_at` " +
                "ON `agent_runs` (`status`, `lease_heartbeat_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_agent_id_created_at` " +
                "ON `agent_runs` (`agent_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_parent_run_id` " +
                "ON `agent_runs` (`parent_run_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_origin_message_id` " +
                "ON `agent_runs` (`origin_message_id`)"
        )

        // ---------------- agent_run_events ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_run_events` (
                `event_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `sequence_number` INTEGER NOT NULL,
                `event_type` TEXT NOT NULL,
                `actor_type` TEXT NOT NULL,
                `actor_id` TEXT,
                `correlation_id` TEXT,
                `causation_event_id` TEXT,
                `idempotency_key` TEXT,
                `occurred_at` INTEGER NOT NULL,
                `recorded_at` INTEGER NOT NULL,
                `payload_schema_version` INTEGER NOT NULL DEFAULT 1,
                `payload_json` TEXT NOT NULL,
                PRIMARY KEY(`event_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_run_events_run_id_sequence_number` " +
                "ON `agent_run_events` (`run_id`, `sequence_number`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_run_events_run_id_event_type_sequence_number` " +
                "ON `agent_run_events` (`run_id`, `event_type`, `sequence_number`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_run_events_run_id_correlation_id` " +
                "ON `agent_run_events` (`run_id`, `correlation_id`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_run_events_run_id_idempotency_key` " +
                "ON `agent_run_events` (`run_id`, `idempotency_key`)"
        )

        // ---------------- agent_run_checkpoints ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_run_checkpoints` (
                `checkpoint_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `checkpoint_sequence` INTEGER NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `checkpoint_schema_version` INTEGER NOT NULL DEFAULT 1,
                `checkpoint_reason` TEXT NOT NULL,
                `state_json` TEXT NOT NULL,
                `state_sha256` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`checkpoint_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_run_checkpoints_run_id_checkpoint_sequence` " +
                "ON `agent_run_checkpoints` (`run_id`, `checkpoint_sequence`)"
        )

        // ---------------- run linkage on the existing tool tables ----------------
        //
        // NULL FOR EVERY EXISTING ROW. A tool call from before durable runs existed had
        // no run, and backfilling one would be inventing provenance for a record nobody
        // was keeping. ADD COLUMN with a nullable type costs a schema change and nothing
        // else, so a table with hundreds of thousands of rows is not rewritten.
        db.execSQL("ALTER TABLE `tool_results` ADD COLUMN `agent_run_id` TEXT")
        db.execSQL("ALTER TABLE `tool_results` ADD COLUMN `agent_invocation_id` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_results_agent_run_id_timestamp` " +
                "ON `tool_results` (`agent_run_id`, `timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_results_agent_run_id_agent_invocation_id` " +
                "ON `tool_results` (`agent_run_id`, `agent_invocation_id`)"
        )

        db.execSQL("ALTER TABLE `tool_observations` ADD COLUMN `agent_run_id` TEXT")
        db.execSQL("ALTER TABLE `tool_observations` ADD COLUMN `agent_invocation_id` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_observations_agent_run_id_timestamp` " +
                "ON `tool_observations` (`agent_run_id`, `timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_observations_agent_run_id_agent_invocation_id` " +
                "ON `tool_observations` (`agent_run_id`, `agent_invocation_id`)"
        )
    }
}
