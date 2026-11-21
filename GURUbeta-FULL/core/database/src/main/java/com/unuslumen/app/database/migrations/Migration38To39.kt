// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 38 to 39: artefacts and context provenance.
 *
 * THE GAP THIS CLOSES. An agent could produce work and there was nowhere to say so: no record of
 * what it made, which run made it, or what it made it from. Separately, GURU's main chat has had a
 * real provenance trail for a while (`memory_injections` records every memory the preamble put in
 * front of the model), and an agent had none, so "why did it say that" had no answer at all.
 *
 * TWO NEW TABLES, PURELY ADDITIVE. No existing row is read, rewritten or dropped.
 *
 * WHY THE ARTEFACT TABLE POINTS RATHER THAN COPIES. GURU already stores project documents, media
 * items and files. Copying a 400MB video into a provenance table to record that an agent made it
 * would be absurd, so `storage_kind` and `storage_ref` name the subsystem and the object, and only
 * a small artefact is carried inline. The size ceiling in the entity says where that line is.
 *
 * WHY CONTEXT REFERENCES ARE SEPARATE FROM MEMORY INJECTIONS. `memory_injections` is the main chat's
 * own ledger and keyed by conversation, not by run. Reusing it would mean an agent's provenance
 * inherited a schema built for a different reader, and every query would have to filter between the
 * two. A run-scoped table answers "what did this execution see" without a join.
 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- agent_artefacts ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_artefacts` (
                `artefact_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `agent_name` TEXT NOT NULL,
                `agent_version` INTEGER NOT NULL DEFAULT 1,
                `title` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL DEFAULT 'text/plain',
                `storage_kind` TEXT NOT NULL,
                `storage_ref` TEXT NOT NULL,
                `content_text` TEXT,
                `size_bytes` INTEGER NOT NULL DEFAULT 0,
                `sources_json` TEXT NOT NULL DEFAULT '[]',
                `final_output` INTEGER NOT NULL DEFAULT 1,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`artefact_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_artefacts_run_id_created_at` " +
                "ON `agent_artefacts` (`run_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_artefacts_agent_id_created_at` " +
                "ON `agent_artefacts` (`agent_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_artefacts_kind` " +
                "ON `agent_artefacts` (`kind`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_artefacts_storage` " +
                "ON `agent_artefacts` (`storage_kind`, `storage_ref`)"
        )

        // ---------------- agent_context_references ----------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_context_references` (
                `reference_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `source_kind` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `source_label` TEXT NOT NULL DEFAULT '',
                `retrieval_query` TEXT NOT NULL DEFAULT '',
                `within_scope` INTEGER NOT NULL DEFAULT 1,
                `scope_label` TEXT NOT NULL DEFAULT '',
                `supplied_to_model` INTEGER NOT NULL DEFAULT 0,
                `excerpt` TEXT NOT NULL DEFAULT '',
                `rank_in_lane` INTEGER NOT NULL DEFAULT 0,
                `score` REAL NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`reference_id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_context_run_id_created_at` " +
                "ON `agent_context_references` (`run_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_context_run_id_supplied` " +
                "ON `agent_context_references` (`run_id`, `supplied_to_model`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_context_source` " +
                "ON `agent_context_references` (`source_kind`, `source_ref`)"
        )
    }
}
