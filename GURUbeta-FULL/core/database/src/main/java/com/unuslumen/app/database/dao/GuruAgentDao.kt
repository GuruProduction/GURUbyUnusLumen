// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.GuruAgentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruAgentDao {

    @Query("SELECT * FROM guru_agents ORDER BY name ASC")
    suspend fun getAllAgents(): List<GuruAgentEntity>

    @Query("SELECT * FROM guru_agents ORDER BY name ASC")
    fun getAllAgentsFlow(): Flow<List<GuruAgentEntity>>

    @Query("SELECT * FROM guru_agents WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledAgents(): List<GuruAgentEntity>

    @Query("SELECT * FROM guru_agents WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledAgentsFlow(): Flow<List<GuruAgentEntity>>

    @Query("SELECT * FROM guru_agents WHERE source = :source ORDER BY name ASC")
    suspend fun getAgentsBySource(source: String): List<GuruAgentEntity>

    @Query("SELECT * FROM guru_agents WHERE id = :id")
    suspend fun getAgentById(id: String): GuruAgentEntity?

    /** Lookup by slug first, because that is the catalogue identity an agent arrives under. */
    @Query("SELECT * FROM guru_agents WHERE slug = :slug LIMIT 1")
    suspend fun getAgentBySlug(slug: String): GuruAgentEntity?

    @Query("SELECT * FROM guru_agents WHERE name = :name LIMIT 1")
    suspend fun getAgentByName(name: String): GuruAgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: GuruAgentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgents(agents: List<GuruAgentEntity>)

    @Update
    suspend fun updateAgent(agent: GuruAgentEntity)

    @Query("UPDATE guru_agents SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM guru_agents WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("DELETE FROM guru_agents WHERE source = :source")
    suspend fun deleteAgentsBySource(source: String)

    /** Retraction support: the synced rows currently installed under a slug. */
    @Query("SELECT * FROM guru_agents WHERE source = 'synced'")
    suspend fun getSyncedAgents(): List<GuruAgentEntity>

    @Query("SELECT COUNT(*) FROM guru_agents")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM guru_agents WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM guru_agents WHERE source = :source")
    suspend fun getCountBySource(source: String): Int
}
