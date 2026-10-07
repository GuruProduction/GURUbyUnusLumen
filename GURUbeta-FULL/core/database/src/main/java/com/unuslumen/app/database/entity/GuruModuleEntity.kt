// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a module — a room GURU builds, fills and tends.
 *
 * A module is the vessel: its composition (HTML/CSS/JS rendered on a canvas
 * that matches the app's theme) is a projection authored by the numen through
 * conversation. The module's own persistent data lives in [dataJson]; the
 * composition itself is versioned via revisions so any room can be rolled
 * back. UI is a projection, never source of truth: rooms that view existing
 * facts (automations, runs, reasoning, money) read the sovereign stores
 * through tools and never shadow them.
 *
 * Status lifecycle: draft → active (registered, door visible in the lobby) →
 * retired (door hidden, data preserved). Deletion is hard-removal of vessel
 * rows on explicit owner confirmation only.
 */
@Entity(
    tableName = "guru_modules",
    indices = [
        Index(value = ["name"], name = "index_guru_modules_name"),
        Index(value = ["status"], name = "index_guru_modules_status"),
        Index(value = ["sort_order"], name = "index_guru_modules_sort_order")
    ]
)
data class GuruModuleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    /** User-worded category from the shape of their life (Home, Money, Comms...), never plumbing. */
    val category: String,
    /** The room's rendering, authored by the numen. HTML for the ModuleCanvas. */
    val compositionHtml: String,
    /** Optional CSS for the room, defaults empty. */
    val compositionCss: String,
    /** Optional JS for the room, defaults empty. */
    val compositionJs: String,
    /** The module's own persistent data space (JSON). View-rooms leave this empty and read sovereign stores instead. */
    val dataJson: String,
    /** Local path to a saved icon for this room's lobby door, null = bundled default. */
    val iconPath: String? = null,
    /** Bumped every time the composition is saved; snapshots land in guru_module_revisions. */
    @ColumnInfo(defaultValue = "0")
    val revision: Int = 0,
    /** DRAFT | ACTIVE | RETIRED */
    @ColumnInfo(defaultValue = "DRAFT")
    val status: String = "DRAFT",
    /** Position in the unified lobby order. Lower = earlier. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    /** Always "guru" in v1 — created through conversation by the numen. */
    val source: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)