// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 24 to 25: the module system's data floor.
 *
 * Four tables:
 * - guru_modules: the vessels — one row per room GURU builds, holding its
 *   composition, its own persistent data space, lobby order and lifecycle.
 * - guru_module_revisions: a full snapshot per save, which is what makes the
 *   rollback verb nearly free.
 * - guru_automation_runs: the Observatory's truth floor. Automation
 *   executions were previously thrown away in-memory; now every run —
 *   manual, job or hook — lands here as it happens with its full step
 *   trace, doctrine statuses only (no "failed").
 * - guru_tile_order: the unified lobby order. Static and grown tiles share
 *   one persisted drag-drop order; the numen reorders by voice through the
 *   same store.
 *
 * Column-for-column identical to the @Entity declarations in
 * entity/GuruModuleEntity.kt, GuruModuleRevisionEntity.kt,
 * GuruAutomationRunEntity.kt, GuruTileOrderEntity.kt — Room validates
 * schema identity against them on open, so any drift is a hard crash
 * rather than silent corruption. Matching Migration23To24's pattern.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Module vessels.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_modules (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                category TEXT NOT NULL,
                compositionHtml TEXT NOT NULL,
                compositionCss TEXT NOT NULL,
                compositionJs TEXT NOT NULL,
                dataJson TEXT NOT NULL,
                iconPath TEXT,
                revision INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'draft',
                sort_order INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_name ON guru_modules (name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_status ON guru_modules (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_modules_sort_order ON guru_modules (sort_order)")

        // 2. Composition revisions — full snapshots for rollback.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_module_revisions (
                id TEXT NOT NULL PRIMARY KEY,
                moduleId TEXT NOT NULL,
                revision INTEGER NOT NULL,
                compositionHtml TEXT NOT NULL,
                compositionCss TEXT NOT NULL,
                compositionJs TEXT NOT NULL,
                dataJson TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_module_revisions_moduleId ON guru_module_revisions (moduleId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_module_revisions_module_revision ON guru_module_revisions (moduleId, revision)")

        // 3. Automation run traces — the Observatory's truth floor.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_automation_runs (
                id TEXT NOT NULL PRIMARY KEY,
                automationId TEXT NOT NULL,
                automationName TEXT NOT NULL,
                trigger TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'running',
                stepsTraceJson TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_automation_runs_automationId ON guru_automation_runs (automationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_automation_runs_started_at ON guru_automation_runs (started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_automation_runs_status ON guru_automation_runs (status)")

        // 4. Unified tile order — one drag-drop surface for the whole lobby.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS guru_tile_order (
                id TEXT NOT NULL PRIMARY KEY,
                sort_order INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_guru_tile_order_sort_order ON guru_tile_order (sort_order)")
    }
}