package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.SeenImageEntity

@Dao
interface SeenImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: SeenImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<SeenImageEntity>)

    @Query("SELECT * FROM seen_images WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): SeenImageEntity?

    @Query("SELECT * FROM seen_images WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(conversationId: String, limit: Int): List<SeenImageEntity>

    @Query("SELECT * FROM seen_images ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentGlobal(limit: Int): List<SeenImageEntity>

    @Query("DELETE FROM seen_images WHERE timestamp < :timestamp")
    suspend fun pruneOlderThan(timestamp: Long)

    @Query("DELETE FROM seen_images")
    suspend fun clear()
}