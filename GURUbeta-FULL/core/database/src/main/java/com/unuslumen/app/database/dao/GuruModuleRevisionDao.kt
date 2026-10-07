// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.GuruModuleRevisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruModuleRevisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRevision(revision: GuruModuleRevisionEntity)

    @Query("SELECT * FROM guru_module_revisions WHERE moduleId = :moduleId ORDER BY revision DESC")
    suspend fun getRevisionsForModule(moduleId: String): List<GuruModuleRevisionEntity>

    @Query("SELECT * FROM guru_module_revisions WHERE moduleId = :moduleId ORDER BY revision DESC")
    fun getRevisionsForModuleFlow(moduleId: String): Flow<List<GuruModuleRevisionEntity>>

    @Query("SELECT * FROM guru_module_revisions WHERE moduleId = :moduleId AND revision = :revision")
    suspend fun getRevision(moduleId: String, revision: Int): GuruModuleRevisionEntity?

    @Query("SELECT * FROM guru_module_revisions WHERE moduleId = :moduleId ORDER BY revision DESC LIMIT 1")
    suspend fun getLatestRevision(moduleId: String): GuruModuleRevisionEntity?

    @Query("SELECT COUNT(*) FROM guru_module_revisions WHERE moduleId = :moduleId")
    suspend fun getRevisionCount(moduleId: String): Int

    @Query("DELETE FROM guru_module_revisions WHERE moduleId = :moduleId")
    suspend fun deleteRevisionsForModule(moduleId: String)

    /**
     * Retention for revision history: keep the newest [keep] snapshots per
     * module, everything older goes. Rollback needs reach, not archaeology.
     */
    @Query("DELETE FROM guru_module_revisions WHERE moduleId = :moduleId AND revision NOT IN (SELECT revision FROM guru_module_revisions WHERE moduleId = :moduleId ORDER BY revision DESC LIMIT :keep)")
    suspend fun pruneRevisions(moduleId: String, keep: Int)
}