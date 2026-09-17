package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.JournalEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Query("SELECT title, SUBSTR(content, 1, 150) AS content, created_date, updated_date, mood, id FROM journal")
    fun getAllEntries(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal")
    suspend fun getAllFullEntries(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun getEntry(id: String): JournalEntryEntity?

    @Query("SELECT title, SUBSTR(content, 1, 100) AS content, created_date, updated_date, mood, id FROM journal WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    suspend fun getEntriesByTitle(query: String): List<JournalEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(journal: JournalEntryEntity)

    @Upsert
    suspend fun upsertEntries(journal: List<JournalEntryEntity>)

    @Update
    suspend fun updateEntry(journal: JournalEntryEntity)

    @Delete
    suspend fun deleteEntry(journal: JournalEntryEntity)

}