package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.PromptAmendmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptAmendmentDao {

    @Query("SELECT * FROM prompt_amendments WHERE section_id = :sectionId ORDER BY created_at DESC")
    suspend fun getAmendmentsForSection(sectionId: String): List<PromptAmendmentEntity>

    @Query("SELECT * FROM prompt_amendments WHERE section_id = :sectionId ORDER BY created_at DESC")
    fun getAmendmentsForSectionFlow(sectionId: String): Flow<List<PromptAmendmentEntity>>

    @Query("SELECT * FROM prompt_amendments WHERE status = :status ORDER BY created_at DESC")
    suspend fun getAmendmentsByStatus(status: String): List<PromptAmendmentEntity>

    @Query("SELECT * FROM prompt_amendments WHERE status = :status ORDER BY created_at DESC")
    fun getAmendmentsByStatusFlow(status: String): Flow<List<PromptAmendmentEntity>>

    @Query("SELECT * FROM prompt_amendments WHERE status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getPendingAmendments(): List<PromptAmendmentEntity>

    @Query("SELECT * FROM prompt_amendments WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun getPendingAmendmentsFlow(): Flow<List<PromptAmendmentEntity>>

    @Query("SELECT * FROM prompt_amendments WHERE status = 'APPROVED' AND section_id = :sectionId ORDER BY approved_at DESC")
    suspend fun getApprovedAmendmentsForSection(sectionId: String): List<PromptAmendmentEntity>

    @Query("SELECT * FROM prompt_amendments WHERE id = :id")
    suspend fun getAmendmentById(id: String): PromptAmendmentEntity?

    @Query("SELECT * FROM prompt_amendments WHERE id = :id")
    fun getAmendmentByIdFlow(id: String): Flow<PromptAmendmentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAmendment(amendment: PromptAmendmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAmendments(amendments: List<PromptAmendmentEntity>)

    @Update
    suspend fun updateAmendment(amendment: PromptAmendmentEntity)

    @Query("UPDATE prompt_amendments SET status = 'APPROVED', approved_at = :approvedAt WHERE id = :id")
    suspend fun approveAmendment(id: String, approvedAt: Long)

    @Query("UPDATE prompt_amendments SET status = 'REJECTED', rejected_at = :rejectedAt WHERE id = :id")
    suspend fun rejectAmendment(id: String, rejectedAt: Long)

    @Query("UPDATE prompt_amendments SET status = 'ROLLED_BACK', rollback_reason = :reason WHERE id = :id")
    suspend fun rollbackAmendment(id: String, reason: String)

    @Query("DELETE FROM prompt_amendments WHERE id = :id")
    suspend fun deleteAmendment(id: String)

    @Query("DELETE FROM prompt_amendments WHERE section_id = :sectionId")
    suspend fun deleteAmendmentsForSection(sectionId: String)

    @Query("SELECT COUNT(*) FROM prompt_amendments WHERE section_id = :sectionId AND status = 'APPROVED'")
    suspend fun getApprovedAmendmentCount(sectionId: String): Int

    @Query("SELECT MAX(version) FROM prompt_amendments WHERE section_id = :sectionId")
    suspend fun getLatestVersion(sectionId: String): Int?
}