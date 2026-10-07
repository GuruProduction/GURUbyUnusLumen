// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.GuruModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruModuleDao {

    @Query("SELECT * FROM guru_modules ORDER BY sort_order ASC, name ASC")
    suspend fun getAllModules(): List<GuruModuleEntity>

    @Query("SELECT * FROM guru_modules ORDER BY sort_order ASC, name ASC")
    fun getAllModulesFlow(): Flow<List<GuruModuleEntity>>

    @Query("SELECT * FROM guru_modules WHERE status = :status ORDER BY sort_order ASC, name ASC")
    suspend fun getModulesByStatus(status: String): List<GuruModuleEntity>

    @Query("SELECT * FROM guru_modules WHERE status = :status ORDER BY sort_order ASC, name ASC")
    fun getModulesByStatusFlow(status: String): Flow<List<GuruModuleEntity>>

    @Query("SELECT * FROM guru_modules WHERE id = :id")
    suspend fun getModuleById(id: String): GuruModuleEntity?

    @Query("SELECT * FROM guru_modules WHERE name = :name")
    suspend fun getModuleByName(name: String): GuruModuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModule(module: GuruModuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModules(modules: List<GuruModuleEntity>)

    @Update
    suspend fun updateModule(module: GuruModuleEntity)

    @Query("UPDATE guru_modules SET compositionHtml = :html, compositionCss = :css, compositionJs = :js, revision = revision + 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun saveComposition(id: String, html: String, css: String, js: String, updatedAt: Long): Int

    @Query("UPDATE guru_modules SET dataJson = :dataJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun saveData(id: String, dataJson: String, updatedAt: Long): Int

    @Query("UPDATE guru_modules SET displayName = :displayName, updated_at = :updatedAt WHERE id = :id")
    suspend fun renameModule(id: String, displayName: String, updatedAt: Long): Int

    @Query("UPDATE guru_modules SET iconPath = :iconPath, updated_at = :updatedAt WHERE id = :id")
    suspend fun setIcon(id: String, iconPath: String?, updatedAt: Long): Int

    @Query("UPDATE guru_modules SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM guru_modules WHERE id = :id")
    suspend fun deleteModule(id: String)

    @Query("SELECT COUNT(*) FROM guru_modules WHERE status = 'active'")
    suspend fun getActiveModuleCount(): Int

    @Query("SELECT COUNT(*) FROM guru_modules WHERE name = :name")
    suspend fun countByName(name: String): Int

    /**
     * Bulk-apply a new unified order. The caller writes sortOrder per row;
     * the lobby and the numen's voice both end here.
     */
    @Query("UPDATE guru_modules SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int, updatedAt: Long)
}