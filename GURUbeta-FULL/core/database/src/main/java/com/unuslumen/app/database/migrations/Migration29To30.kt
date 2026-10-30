// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 29 to 30: the memory injection ledger.
 *
 * THE GAP THIS CLOSES. The preamble builder decided which memories to inject
 * into a model turn and recorded nothing about it. That made retrieval quality
 * unmeasurable on real data: the only evidence was a handful of hand-written
 * pairs from whoever wrote the last test.
 *
 * The ledger turns ordinary use into labels for free. The valuable one is the
 * recall failure: when a user restates a fact the preamble did not inject ("I
 * told you, the key's in the kitchen"), that is ground truth the user produced
 * by accident, from the real distribution, at no cost. It only exists if
 * injection is logged first. That is why this table lands before the semantic
 * lane rather than after it.
 *
 * A new table, purely additive: no rebuilds and no existing row is touched.
 * Column names, types and defaults are identical to MemoryInjectionEntity
 * because Room validates schema identity on open, and a mismatch crashes on
 * first launch after upgrade rather than failing quietly.
 *
 * Privacy: memory ids, query text and lane scores only. Never memory bodies. A
 * row says "memory X was believed relevant to this query", which is derivable
 * from the brain and the conversation anyway. It stays on device and is pruned
 * on the same 30-day horizon as tool results.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_injections` (
                `id` TEXT NOT NULL,
                `memory_id` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `lane` TEXT NOT NULL,
                `rank_in_lane` INTEGER NOT NULL,
                `score` REAL NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `confirmed_used_at` INTEGER,
                `restated_without_injection` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_injections_conversation_id` ON `memory_injections` (`conversation_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_injections_timestamp` ON `memory_injections` (`timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_injections_memory_id` ON `memory_injections` (`memory_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_injections_lane` ON `memory_injections` (`lane`)"
        )
    }
}
