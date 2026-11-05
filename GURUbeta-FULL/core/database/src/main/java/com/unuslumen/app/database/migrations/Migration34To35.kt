// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 34 to 35: the heartbeat diary.
 *
 * THE GAP THIS CLOSES. The heartbeat fires on every absolute half hour for the life of
 * the app. Until now its owner could write one master prompt and nothing else, so all
 * forty-eight beats in a day said the same thing. This adds a bookings diary: a real
 * calendar in which any half hour can be claimed and given its own instruction, on a
 * repeat rule, with every occurrence materialised as a dated entry so the diary can
 * report what actually happened in each one.
 *
 * TWO NEW TABLES, AND NOT ONE MORE. A rule and its occurrences are different questions.
 * The rule is what the owner wrote and can edit; the occurrences are real half hours,
 * each with the outcome of the beat that ran in it. A table holding only rules could
 * not say "did my nine o'clock run" or "did I miss it while the phone was asleep", and
 * a table holding only occurrences could not let the owner edit next Tuesday and have
 * every future Tuesday follow.
 *
 * PURELY ADDITIVE. No existing row is read, written or dropped, so an upgrading install
 * keeps its prompt, its switch, its conversation and its whole diary of messages. The
 * prompt table from migration 32 to 33 is untouched.
 *
 * COLUMN NAMES, TYPES, NULLABILITY AND DEFAULTS ARE TAKEN FROM THE ENTITY DEFINITIONS
 * rather than written from memory, because Room validates schema identity on open and a
 * mismatch crashes on the first launch after an upgrade instead of failing quietly. The
 * column order here mirrors the entities exactly for the same reason: Room compares the
 * statement it expects against the one the entity produces, and a column in a different
 * position is a mismatch even when the name and type are identical.
 *
 * THE UNIQUE INDEX IS THE RULE THAT TWO OCCURRENCES OF ONE BOOKING CANNOT SHARE A HALF
 * HOUR, enforced by the database rather than by a check the room could forget to make.
 * A second insert on the same (booking, slot) pair is simply ignored, so topping up the
 * horizon twice cannot duplicate a day.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `heartbeat_bookings` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `instruction` TEXT NOT NULL,
                `repeat_kind` TEXT NOT NULL,
                `days_mask` INTEGER NOT NULL DEFAULT 0,
                `combine` TEXT NOT NULL,
                `colour_index` INTEGER NOT NULL DEFAULT 0,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `starts_at` INTEGER NOT NULL,
                `ends_at` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_heartbeat_bookings_enabled` " +
                "ON `heartbeat_bookings` (`enabled`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_heartbeat_bookings_starts_at` " +
                "ON `heartbeat_bookings` (`starts_at`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `heartbeat_entries` (
                `id` TEXT NOT NULL,
                `booking_id` TEXT NOT NULL,
                `slot_at` INTEGER NOT NULL,
                `override_instruction` TEXT,
                `state` TEXT NOT NULL,
                `fired_at` INTEGER NOT NULL DEFAULT 0,
                `finished_at` INTEGER NOT NULL DEFAULT 0,
                `outcome` TEXT NOT NULL DEFAULT '',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_heartbeat_entries_slot_at` " +
                "ON `heartbeat_entries` (`slot_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_heartbeat_entries_booking` " +
                "ON `heartbeat_entries` (`booking_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_heartbeat_entries_state` " +
                "ON `heartbeat_entries` (`state`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_heartbeat_entries_booking_slot` " +
                "ON `heartbeat_entries` (`booking_id`, `slot_at`)"
        )
    }
}
