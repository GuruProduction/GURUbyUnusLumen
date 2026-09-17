package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.GuruDefinedToolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruDefinedToolDao {

    @Query("SELECT * FROM guru_defined_tools ORDER BY created_at DESC")
    suspend fun getAllTools(): List<GuruDefinedToolEntity>

    @Query("SELECT * FROM guru_defined_tools ORDER BY created_at DESC")
    fun getAllToolsFlow(): Flow<List<GuruDefinedToolEntity>>

    @Query("SELECT * FROM guru_defined_tools WHERE status = :status ORDER BY created_at DESC")
    suspend fun getToolsByStatus(status: String): List<GuruDefinedToolEntity>

    @Query("SELECT * FROM guru_defined_tools WHERE status = 'APPROVED' ORDER BY name ASC")
    suspend fun getApprovedTools(): List<GuruDefinedToolEntity>

    @Query("SELECT * FROM guru_defined_tools WHERE status = 'APPROVED' ORDER BY name ASC")
    fun getApprovedToolsFlow(): Flow<List<GuruDefinedToolEntity>>

    @Query("SELECT * FROM guru_defined_tools WHERE status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getPendingTools(): List<GuruDefinedToolEntity>

    @Query("SELECT * FROM guru_defined_tools WHERE id = :id")
    suspend fun getToolById(id: String): GuruDefinedToolEntity?

    @Query("SELECT * FROM guru_defined_tools WHERE name = :name")
    suspend fun getToolByName(name: String): GuruDefinedToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: GuruDefinedToolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTools(tools: List<GuruDefinedToolEntity>)

    @Update
    suspend fun updateTool(tool: GuruDefinedToolEntity)

    @Query("UPDATE guru_defined_tools SET status = 'APPROVED', approved_at = :approvedAt WHERE id = :id")
    suspend fun approveTool(id: String, approvedAt: Long)

    @Query("UPDATE guru_defined_tools SET status = 'DISABLED' WHERE id = :id")
    suspend fun disableTool(id: String)

    @Query("UPDATE guru_defined_tools SET status = 'PENDING' WHERE id = :id")
    suspend fun setToolPending(id: String)

    @Query("UPDATE guru_defined_tools SET last_used_at = :lastUsedAt, use_count = use_count + 1 WHERE id = :id")
    suspend fun recordToolUsage(id: String, lastUsedAt: Long)

    @Query("DELETE FROM guru_defined_tools WHERE id = :id")
    suspend fun deleteTool(id: String)

    @Query("DELETE FROM guru_defined_tools")
    suspend fun deleteAllTools()

    @Query("SELECT COUNT(*) FROM guru_defined_tools WHERE status = 'PENDING'")
    suspend fun getPendingToolCount(): Int
}