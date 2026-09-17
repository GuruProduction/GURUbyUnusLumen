package com.unuslumen.app.database.dao

import androidx.room.*
import com.unuslumen.app.database.entity.GuruInsightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruInsightDao {
    
    @Query("SELECT * FROM guru_insights ORDER BY createdAt DESC")
    suspend fun getAllInsights(): List<GuruInsightEntity>
    
    @Query("SELECT * FROM guru_insights ORDER BY createdAt DESC")
    fun getAllInsightsFlow(): Flow<List<GuruInsightEntity>>
    
    @Query("SELECT * FROM guru_insights WHERE cycleId = :cycleId ORDER BY createdAt DESC")
    suspend fun getInsightsByCycle(cycleId: String): List<GuruInsightEntity>
    
    @Query("SELECT * FROM guru_insights WHERE cycleId = :cycleId ORDER BY createdAt DESC")
    fun getInsightsByCycleFlow(cycleId: String): Flow<List<GuruInsightEntity>>
    
    @Query("SELECT * FROM guru_insights WHERE type = :type ORDER BY createdAt DESC")
    suspend fun getInsightsByType(type: String): List<GuruInsightEntity>
    
    @Query("SELECT * FROM guru_insights WHERE acknowledgedAt IS NULL AND dismissedAt IS NULL ORDER BY createdAt DESC")
    suspend fun getUnacknowledgedInsights(): List<GuruInsightEntity>
    
    @Query("SELECT * FROM guru_insights WHERE acknowledgedAt IS NULL AND dismissedAt IS NULL ORDER BY createdAt DESC")
    fun getUnacknowledgedInsightsFlow(): Flow<List<GuruInsightEntity>>
    
    @Query("SELECT * FROM guru_insights WHERE actionable = 1 AND actionTaken = 0 ORDER BY createdAt DESC")
    suspend fun getActionableInsights(): List<GuruInsightEntity>
    
    @Query("SELECT * FROM guru_insights WHERE id = :id")
    suspend fun getInsightById(id: String): GuruInsightEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInsight(insight: GuruInsightEntity)
    
    @Update
    suspend fun updateInsight(insight: GuruInsightEntity)
    
    @Query("UPDATE guru_insights SET acknowledgedAt = :timestamp WHERE id = :id")
    suspend fun acknowledge(id: String, timestamp: Long)
    
    @Query("UPDATE guru_insights SET dismissedAt = :timestamp WHERE id = :id")
    suspend fun dismiss(id: String, timestamp: Long)
    
    @Query("UPDATE guru_insights SET actionTaken = 1, actionType = :actionType, actionId = :actionId WHERE id = :id")
    suspend fun markActionTaken(id: String, actionType: String, actionId: String)
    
    @Delete
    suspend fun deleteInsight(insight: GuruInsightEntity)
    
    @Query("DELETE FROM guru_insights WHERE id = :id")
    suspend fun deleteInsightById(id: String)
    
    @Query("DELETE FROM guru_insights WHERE createdAt < :timestamp")
    suspend fun deleteOldInsights(timestamp: Long)
}