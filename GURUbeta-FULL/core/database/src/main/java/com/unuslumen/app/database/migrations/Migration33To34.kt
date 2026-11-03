// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 33 to 34: the network map. Three tables, so what GURU sees on a network survives a
 * restart and accumulates into something it can reason about later.
 *
 * WHY THREE TABLES AND NOT ONE. One row per device holds only the present, and every question worth
 * asking about a network is a question about change: when did this appear, when did it stop
 * answering, did its address move, did a port open that was shut last week. A sweep that found
 * twenty devices is one row in `network_scans`, twenty rows in `network_sightings` and twenty rows
 * touched in `network_devices`. Collapsing those would throw away the history that makes the map
 * worth having, and collapsing them the other way would mean re-deriving the present from the past
 * on every read.
 *
 * PURELY ADDITIVE, like every migration before it. No existing row is touched, so this cannot
 * corrupt anything already working. Column names, types, nullability and defaults are taken from
 * the entity definitions in NetworkVisionEntities rather than written from memory, because Room
 * validates schema identity on open and a mismatch crashes on the first launch after an upgrade
 * rather than failing quietly.
 *
 * THE COLUMN ORDER MATTERS AND MIRRORS THE ENTITIES EXACTLY. Room's generated schema compares the
 * statement it expects against the one the entity produces, and a column in a different position is
 * a mismatch even when the type and name are identical.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_devices` (
                `id` TEXT NOT NULL,
                `identity_kind` TEXT NOT NULL,
                `mac` TEXT,
                `last_address` TEXT NOT NULL,
                `name` TEXT,
                `room` TEXT,
                `observed_name` TEXT,
                `vendor` TEXT,
                `model` TEXT,
                `firmware` TEXT,
                `device_class` TEXT NOT NULL,
                `open_ports_json` TEXT NOT NULL DEFAULT '[]',
                `services_json` TEXT NOT NULL DEFAULT '[]',
                `sighting_count` INTEGER NOT NULL DEFAULT 1,
                `first_seen` INTEGER NOT NULL,
                `last_seen` INTEGER NOT NULL,
                `retired` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_devices_mac` ON `network_devices` (`mac`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_devices_class` ON `network_devices` (`device_class`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_devices_vendor` ON `network_devices` (`vendor`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_devices_last_seen` ON `network_devices` (`last_seen`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_devices_first_seen` ON `network_devices` (`first_seen`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_sightings` (
                `id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `scan_id` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `open_ports_json` TEXT NOT NULL DEFAULT '[]',
                `services_json` TEXT NOT NULL DEFAULT '[]',
                `evidence_json` TEXT NOT NULL DEFAULT '[]',
                `device_class` TEXT NOT NULL,
                `answered` INTEGER NOT NULL,
                `seen_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sightings_device` ON `network_sightings` (`device_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sightings_scan` ON `network_sightings` (`scan_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sightings_seen_at` ON `network_sightings` (`seen_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sightings_address` ON `network_sightings` (`address`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_scans` (
                `id` TEXT NOT NULL,
                `subnet` TEXT,
                `trigger` TEXT NOT NULL,
                `prompt` TEXT,
                `started_at` INTEGER NOT NULL,
                `finished_at` INTEGER NOT NULL DEFAULT 0,
                `probed_addresses` INTEGER NOT NULL DEFAULT 0,
                `alive_addresses` INTEGER NOT NULL DEFAULT 0,
                `device_count` INTEGER NOT NULL DEFAULT 0,
                `problems_json` TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_scans_started_at` ON `network_scans` (`started_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_scans_subnet` ON `network_scans` (`subnet`)")
    }
}
