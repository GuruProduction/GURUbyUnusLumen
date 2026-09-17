package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruNoteToSelfDao {

    @Query("SELECT * FROM guru_notes_to_self ORDER BY created_at DESC")
    suspend fun getAll(): List<GuruNoteToSelfEntity>

    @Query("SELECT * FROM guru_notes_to_self ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<GuruNoteToSelfEntity>>

    @Query("SELECT * FROM guru_notes_to_self WHERE enabled = 1")
    suspend fun getEnabled(): List<GuruNoteToSelfEntity>

    @Query("SELECT * FROM guru_notes_to_self WHERE id = :id")
    suspend fun getById(id: String): GuruNoteToSelfEntity?

    @Query("SELECT * FROM guru_notes_to_self WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): GuruNoteToSelfEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: GuruNoteToSelfEntity)

    @Update
    suspend fun update(note: GuruNoteToSelfEntity)

    @Query("UPDATE guru_notes_to_self SET enabled = :enabled, updated_at = :timestamp WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, timestamp: Long)

    @Query("UPDATE guru_notes_to_self SET last_fired_at = :timestamp, fire_count = fire_count + 1 WHERE id = :id")
    suspend fun recordFire(id: String, timestamp: Long)

    @Query("DELETE FROM guru_notes_to_self WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM guru_notes_to_self")
    suspend fun count(): Int
}