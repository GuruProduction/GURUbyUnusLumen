package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.NoteEntity
import com.unuslumen.app.database.entity.NoteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT title, SUBSTR(content, 1, 150) AS content, created_date, updated_date, pinned, folder_id, id FROM notes WHERE folder_id IS NULL")
    fun getAllFolderlessNotes(): Flow<List<NoteEntity>>

    @Query("SELECT title, SUBSTR(content, 1, 150) AS content, created_date, updated_date, pinned, folder_id, id FROM notes")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun getAllFullNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): NoteEntity?

    @Query("SELECT title, SUBSTR(content, 1, 100) AS content, created_date, updated_date, pinned, folder_id, id FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    suspend fun getNotesByTitle(query: String): List<NoteEntity>

    @Query("SELECT title, SUBSTR(content, 1, 150) AS content, created_date, updated_date, pinned, folder_id, id FROM notes WHERE folder_id = :folderId")
    fun getNotesByFolder(folderId: String): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes WHERE folder_id = :folderId")
    suspend fun deleteNotesByFolderId(folderId: String)

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Upsert
    suspend fun upsertNotes(notes: List<NoteEntity>)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNoteFolder(folder: NoteFolderEntity)

    @Upsert
    suspend fun upsertNoteFolders(folders: List<NoteFolderEntity>)

    @Update
    suspend fun updateNoteFolder(folder: NoteFolderEntity)

    @Delete
    suspend fun deleteNoteFolder(folder: NoteFolderEntity)

    @Query("DELETE FROM note_folders WHERE id = :folderId")
    suspend fun deleteNoteFolderById(folderId: String)

    @Transaction
    suspend fun deleteFolderAndNotes(folderId: String) {
        deleteNotesByFolderId(folderId)
        deleteNoteFolderById(folderId)
    }

    @Query("SELECT * FROM note_folders")
    fun getAllNoteFolders(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM note_folders WHERE id = :folderId")
    fun getNoteFolder(folderId: String): NoteFolderEntity?

    @Query("SELECT * FROM note_folders WHERE name = :name")
    suspend fun getNoteFolderByName(name: String): NoteFolderEntity?

    @Query("SELECT * FROM note_folders WHERE name LIKE '%' || :name || '%'")
    fun searchFolderByName(name: String): List<NoteFolderEntity>
}