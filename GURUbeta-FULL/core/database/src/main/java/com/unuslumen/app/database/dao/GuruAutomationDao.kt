package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.GuruAutomationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruAutomationDao {

    @Query("SELECT * FROM guru_automations ORDER BY name ASC")
    suspend fun getAllAutomations(): List<GuruAutomationEntity>

    @Query("SELECT * FROM guru_automations ORDER BY name ASC")
    fun getAllAutomationsFlow(): Flow<List<GuruAutomationEntity>>

    @Query("SELECT * FROM guru_automations WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledAutomations(): List<GuruAutomationEntity>

    @Query("SELECT * FROM guru_automations WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledAutomationsFlow(): Flow<List<GuruAutomationEntity>>

    @Query("SELECT * FROM guru_automations WHERE trigger = :trigger AND enabled = 1")
    suspend fun getAutomationsByTrigger(trigger: String): List<GuruAutomationEntity>

    @Query("SELECT * FROM guru_automations WHERE id = :id")
    suspend fun getAutomationById(id: String): GuruAutomationEntity?

    @Query("SELECT * FROM guru_automations WHERE name = :name")
    suspend fun getAutomationByName(name: String): GuruAutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: GuruAutomationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomations(automations: List<GuruAutomationEntity>)

    @Update
    suspend fun updateAutomation(automation: GuruAutomationEntity)

    @Query("UPDATE guru_automations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE guru_automations SET last_run_at = :lastRunAt, run_count = run_count + 1 WHERE id = :id")
    suspend fun recordRun(id: String, lastRunAt: Long)

    @Query("DELETE FROM guru_automations WHERE id = :id")
    suspend fun deleteAutomation(id: String)

    @Query("DELETE FROM guru_automations")
    suspend fun deleteAllAutomations()

    @Query("SELECT COUNT(*) FROM guru_automations WHERE enabled = 1")
    suspend fun getEnabledAutomationCount(): Int
}