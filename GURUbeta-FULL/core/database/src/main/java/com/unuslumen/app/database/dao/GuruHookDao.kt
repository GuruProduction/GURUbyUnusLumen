package com.unuslumen.app.database.dao

import androidx.room.*
import com.unuslumen.app.database.entity.GuruHookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruHookDao {
    
    @Query("SELECT * FROM guru_hooks ORDER BY priority ASC, name ASC")
    suspend fun getAllHooks(): List<GuruHookEntity>
    
    @Query("SELECT * FROM guru_hooks ORDER BY priority ASC, name ASC")
    fun getAllHooksFlow(): Flow<List<GuruHookEntity>>
    
    @Query("SELECT * FROM guru_hooks WHERE enabled = 1 ORDER BY priority ASC, name ASC")
    suspend fun getEnabledHooks(): List<GuruHookEntity>
    
    @Query("SELECT * FROM guru_hooks WHERE enabled = 1 ORDER BY priority ASC, name ASC")
    fun getEnabledHooksFlow(): Flow<List<GuruHookEntity>>
    
    @Query("SELECT * FROM guru_hooks WHERE eventType = :eventType AND enabled = 1 ORDER BY priority ASC")
    suspend fun getHooksByEventType(eventType: String): List<GuruHookEntity>
    
    @Query("SELECT * FROM guru_hooks WHERE eventType = :eventType AND triggerTiming = :timing AND enabled = 1 ORDER BY priority ASC")
    suspend fun getHooksByEventTypeAndTiming(eventType: String, timing: String): List<GuruHookEntity>
    
    @Query("SELECT * FROM guru_hooks WHERE id = :id")
    suspend fun getHookById(id: String): GuruHookEntity?
    
    @Query("SELECT * FROM guru_hooks WHERE name = :name")
    suspend fun getHookByName(name: String): GuruHookEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHook(hook: GuruHookEntity)
    
    @Update
    suspend fun updateHook(hook: GuruHookEntity)
    
    @Query("UPDATE guru_hooks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
    
    @Query("UPDATE guru_hooks SET lastTriggeredAt = :timestamp, triggerCount = triggerCount + 1 WHERE id = :id")
    suspend fun recordTrigger(id: String, timestamp: Long)
    
    @Delete
    suspend fun deleteHook(hook: GuruHookEntity)
    
    @Query("DELETE FROM guru_hooks WHERE id = :id")
    suspend fun deleteHookById(id: String)
}