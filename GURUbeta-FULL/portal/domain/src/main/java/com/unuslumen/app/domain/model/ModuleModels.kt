// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A module's lifecycle.
 *
 * draft: composed but no lobby door yet. active: registered, door visible.
 * retired: door hidden, everything preserved.
 */
enum class ModuleStatus {
    DRAFT, ACTIVE, RETIRED;

    companion object {
        fun fromRaw(raw: String): ModuleStatus =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: DRAFT
    }
}

/**
 * A room GURU builds, fills and tends.
 *
 * The composition is a projection authored by the numen; the room's own
 * persistent data lives in [dataJson]. View-rooms read the sovereign
 * stores through tools and never shadow them; own-rooms keep everything
 * here. Every save bumps [revision] and snapshots to guru_module_revisions,
 * which makes rollback nearly free.
 */
@Serializable
data class GuruModule(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    /** User-worded category (Home, Money, Comms...), never plumbing. */
    val category: String,
    val compositionHtml: String,
    val compositionCss: String,
    val compositionJs: String,
    val dataJson: String,
    val iconPath: String? = null,
    val revision: Int = 0,
    val status: ModuleStatus = ModuleStatus.DRAFT,
    val sortOrder: Int = 0,
    val source: String = "guru",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Request to create a new blank vessel. The composition arrives later,
 * through conversation, module-forge doctrine in hand.
 */
@Serializable
data class CreateModuleRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val category: String
)

@Serializable
data class ModuleRevision(
    val id: String,
    val moduleId: String,
    val revision: Int,
    val compositionHtml: String,
    val compositionCss: String,
    val compositionJs: String,
    val dataJson: String,
    val createdAt: Long
)

@Serializable
data class ModuleSummary(
    val totalModules: Int,
    val activeModules: Int,
    val draftModules: Int,
    val retiredModules: Int
)

/**
 * One lobby tile, unified order. Static and grown tiles share this shape
 * so the whole lobby drags as one surface and the numen can reorder by
 * voice through the same store.
 */
@Serializable
data class TileOrder(
    /** "module:<id>" or "static:<key>" */
    val id: String,
    val sortOrder: Int
)

/** Revision retention: rooms iterate fast, rollback needs reach not archaeology. */
object ModuleRetention {
    const val REVISIONS_PER_MODULE = 20
}