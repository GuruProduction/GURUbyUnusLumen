package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.PromptSectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptSectionDao {

    @Query("SELECT * FROM prompt_sections ORDER BY section_order ASC")
    suspend fun getAllSections(): List<PromptSectionEntity>

    @Query("SELECT * FROM prompt_sections ORDER BY section_order ASC")
    fun getAllSectionsFlow(): Flow<List<PromptSectionEntity>>

    @Query("SELECT * FROM prompt_sections WHERE id = :id")
    suspend fun getSectionById(id: String): PromptSectionEntity?

    @Query("SELECT * FROM prompt_sections WHERE id = :id")
    fun getSectionByIdFlow(id: String): Flow<PromptSectionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSection(section: PromptSectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<PromptSectionEntity>)

    @Update
    suspend fun updateSection(section: PromptSectionEntity)

    @Query("DELETE FROM prompt_sections WHERE id = :id")
    suspend fun deleteSection(id: String)

    @Query("DELETE FROM prompt_sections")
    suspend fun deleteAllSections()
}