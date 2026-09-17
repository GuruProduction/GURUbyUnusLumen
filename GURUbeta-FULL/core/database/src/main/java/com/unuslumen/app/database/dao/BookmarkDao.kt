package com.unuslumen.app.database.dao

import androidx.room.*
import com.unuslumen.app.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAllFullBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getBookmark(id: String): BookmarkEntity

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%'")
    suspend fun searchBookmarks(query: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertBookmarks(bookmarks: List<BookmarkEntity>)

}