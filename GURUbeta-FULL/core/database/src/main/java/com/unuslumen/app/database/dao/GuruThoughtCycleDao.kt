package com.unuslumen.app.database.dao

import androidx.room.*
import com.unuslumen.app.database.entity.GuruThoughtCycleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruThoughtCycleDao {
    
    @Query("SELECT * FROM guru_thought_cycles ORDER BY name ASC")
    suspend fun getAllCycles(): List<GuruThoughtCycleEntity>
    
    @Query("SELECT * FROM guru_thought_cycles ORDER BY name ASC")
    fun getAllCyclesFlow(): Flow<List<GuruThoughtCycleEntity>>
    
    @Query("SELECT * FROM guru_thought_cycles WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledCycles(): List<GuruThoughtCycleEntity>
    
    @Query("SELECT * FROM guru_thought_cycles WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledCyclesFlow(): Flow<List<GuruThoughtCycleEntity>>
    
    @Query("SELECT * FROM guru_thought_cycles WHERE id = :id")
    suspend fun getCycleById(id: String): GuruThoughtCycleEntity?
    
    @Query("SELECT * FROM guru_thought_cycles WHERE name = :name")
    suspend fun getCycleByName(name: String): GuruThoughtCycleEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycle(cycle: GuruThoughtCycleEntity)
    
    @Update
    suspend fun updateCycle(cycle: GuruThoughtCycleEntity)
    
    @Query("UPDATE guru_thought_cycles SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
    
    @Query("UPDATE guru_thought_cycles SET lastRunAt = :timestamp, lastResult = :result, runCount = runCount + 1 WHERE id = :id")
    suspend fun recordRun(id: String, timestamp: Long, result: String?)
    
    @Query("UPDATE guru_thought_cycles SET insightCount = insightCount + 1 WHERE id = :id")
    suspend fun incrementInsightCount(id: String)
    
    @Query("UPDATE guru_thought_cycles SET actionCount = actionCount + 1 WHERE id = :id")
    suspend fun incrementActionCount(id: String)
    
    @Query("UPDATE guru_thought_cycles SET proposalCount = proposalCount + 1 WHERE id = :id")
    suspend fun incrementProposalCount(id: String)
    
    @Delete
    suspend fun deleteCycle(cycle: GuruThoughtCycleEntity)
    
    @Query("DELETE FROM guru_thought_cycles WHERE id = :id")
    suspend fun deleteCycleById(id: String)
}