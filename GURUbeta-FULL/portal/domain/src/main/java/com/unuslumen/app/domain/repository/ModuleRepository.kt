// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.CreateModuleRequest
import com.unuslumen.app.domain.model.GuruModule
import com.unuslumen.app.domain.model.ModuleRevision
import com.unuslumen.app.domain.model.ModuleStatus
import com.unuslumen.app.domain.model.ModuleSummary
import com.unuslumen.app.domain.model.TileOrder
import kotlinx.coroutines.flow.Flow

/**
 * Repository for module vessels — the rooms GURU builds.
 *
 * Creation goes through conversation: a blank vessel first, composition
 * saved in iterations, door registered when the human approves. Every
 * composition save snapshots a revision so rollback restores any prior
 * room. Deletion is hard and final, gated on explicit owner confirmation
 * by the caller (the tools relay the confirm; the repository trusts that).
 */
interface ModuleRepository {

    // ==================== Vessel lifecycle ====================

    suspend fun createModule(request: CreateModuleRequest): GuruModule

    suspend fun getModule(id: String): GuruModule?

    suspend fun getModuleByName(name: String): GuruModule?

    suspend fun getAllModules(): List<GuruModule>

    fun getAllModulesFlow(): Flow<List<GuruModule>>

    suspend fun getModulesByStatus(status: ModuleStatus): List<GuruModule>

    fun getModulesByStatusFlow(status: ModuleStatus): Flow<List<GuruModule>>

    suspend fun renameModule(id: String, displayName: String): GuruModule

    suspend fun setIcon(id: String, iconPath: String?): GuruModule

    suspend fun isNameAvailable(name: String): Boolean

    // ==================== Composition ====================

    /**
     * Save a new composition for the room. Bumps the revision, writes a
     * full snapshot to revisions, prunes old snapshots per retention.
     */
    suspend fun saveComposition(id: String, html: String, css: String, js: String): GuruModule

    /**
     * Save the room's own persistent data space. View-rooms read sovereign
     * stores instead and leave this for own-rooms.
     */
    suspend fun saveData(id: String, dataJson: String): GuruModule

    suspend fun getData(id: String): String?

    suspend fun getRevisions(id: String): List<ModuleRevision>

    suspend fun getRevision(id: String, revision: Int): ModuleRevision?

    /**
     * Rollback: restore a prior snapshot as the live composition. The
     * restore itself saves a new revision (history never rewrites).
     */
    suspend fun rollbackToRevision(id: String, revision: Int): GuruModule

    // ==================== Door ====================

    /** Register: door appears in the lobby at the top of the order. */
    suspend fun registerModule(id: String): GuruModule

    /** Retire: door hides, everything preserved. */
    suspend fun retireModule(id: String): GuruModule

    /**
     * Hard delete on explicit owner confirmation only. Purges the vessel,
     * its revisions. Automation runs survive — they are sovereign history
     * belonging to the automations, not to any room.
     */
    suspend fun deleteModule(id: String)

    suspend fun getSummary(): ModuleSummary

    // ==================== Lobby order ====================

    /** Unified order, static + grown tiles, one drag surface. */
    suspend fun getTileOrders(): List<TileOrder>

    fun getTileOrdersFlow(): Flow<List<TileOrder>>

    /**
     * Persist a full reorder. Caller supplies the complete ordered list;
     * a partial list would strand tiles.
     */
    suspend fun saveTileOrders(orders: List<TileOrder>)

    /**
     * A new registered module joins at the top of the lobby by default
     * (per doctrine: the room the numen just built leads).
     */
    suspend fun joinAtTop(id: String)
}