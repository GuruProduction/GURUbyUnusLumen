// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 30 to 31: the smart home device fabric. Issue #182.
 *
 * THE GAP THIS CLOSES. The smart home tools were shells around CLIs that do not
 * exist (issue #179), so nothing on the phone knew what a device was. This adds
 * one table for devices and one for scenes, so a protocol can report a device
 * and that device survives a restart.
 *
 * TWO NEW TABLES, purely additive. No existing row is touched, so this migration
 * cannot corrupt anything that already works. Column names, types, nullability
 * and defaults are identical to FabricDeviceEntity and FabricSceneEntity because
 * Room validates schema identity on open, and a mismatch crashes on first launch
 * after upgrade rather than failing quietly.
 *
 * Room's generated schema expects the exact column order and defaults, so these
 * statements were taken from the entity definitions rather than written from
 * memory.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fabric_devices` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `room` TEXT,
                `transport` TEXT NOT NULL,
                `device_class` TEXT NOT NULL,
                `capabilities_json` TEXT NOT NULL,
                `state_json` TEXT NOT NULL,
                `raw_json` TEXT NOT NULL,
                `available` INTEGER NOT NULL,
                `last_seen` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fabric_devices_room` ON `fabric_devices` (`room`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fabric_devices_transport` ON `fabric_devices` (`transport`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fabric_devices_class` ON `fabric_devices` (`device_class`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fabric_devices_available` ON `fabric_devices` (`available`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fabric_scenes` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `states_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_fabric_scenes_name` ON `fabric_scenes` (`name`)"
        )
    }
}
