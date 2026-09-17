package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.HiveMindStateEntity

@Dao
interface HiveMindStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: HiveMindStateEntity)

    @Query("SELECT * FROM hive_mind_state WHERE id = 1")
    suspend fun getState(): HiveMindStateEntity?

    @Query("UPDATE hive_mind_state SET last_processed_conversation_id = :conversationId, last_processed_timestamp = :timestamp, total_facts_extracted = total_facts_extracted + :factsAdded, last_processing_start = :processingStart, last_processing_end = :processingEnd, is_processing = :isProcessing WHERE id = 1")
    suspend fun updateProcessedState(conversationId: String, timestamp: Long, factsAdded: Int, processingStart: Long, processingEnd: Long, isProcessing: Boolean)

    @Query("UPDATE hive_mind_state SET total_threads_discovered = total_threads_discovered + :threadsAdded WHERE id = 1")
    suspend fun updateThreadCount(threadsAdded: Int)

    @Query("UPDATE hive_mind_state SET total_facts_extracted = total_facts_extracted + :factsAdded WHERE id = 1")
    suspend fun updateFactCount(factsAdded: Int)

    @Query("UPDATE hive_mind_state SET is_processing = :isProcessing, last_processing_start = :timestamp WHERE id = 1")
    suspend fun setProcessing(isProcessing: Boolean, timestamp: Long)
}
