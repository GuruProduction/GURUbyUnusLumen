package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.MemoryEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: MemoryEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<MemoryEventEntity>)

    @Query("SELECT * FROM memory_events WHERE enriched = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnenrichedEvents(limit: Int = 100): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_events WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getEventsForConversation(conversationId: String): List<MemoryEventEntity>

    @Query("UPDATE memory_events SET enriched = 1 WHERE id = :eventId")
    suspend fun markEnriched(eventId: String)

    @Query("UPDATE memory_events SET enriched = 1 WHERE id IN (:eventIds)")
    suspend fun markBatchEnriched(eventIds: List<String>)

    @Query("SELECT COUNT(*) FROM memory_events")
    suspend fun getEventCount(): Int

    @Query("SELECT COUNT(*) FROM memory_events WHERE enriched = 0")
    suspend fun getUnenrichedCount(): Int

    @Query("SELECT * FROM memory_events ORDER BY created_at DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int = 50): Flow<List<MemoryEventEntity>>
}
