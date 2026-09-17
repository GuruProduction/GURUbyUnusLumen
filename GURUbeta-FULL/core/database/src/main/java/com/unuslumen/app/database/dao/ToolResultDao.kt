package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.database.entity.ToolResultWithScore

@Dao
interface ToolResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolResult(result: ToolResultEntity)

    @Query("SELECT * FROM tool_results WHERE tool_name = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByToolName(toolName: String, limit: Int): List<ToolResultEntity>

    @Query("SELECT * FROM tool_results WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByConversation(conversationId: String, limit: Int): List<ToolResultEntity>

    @Query("SELECT * FROM tool_results WHERE tool_name = :toolName AND conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByToolNameAndConversation(toolName: String, conversationId: String, limit: Int): List<ToolResultEntity>

    @SkipQueryVerification
    @Query("""
        SELECT t.* FROM tool_results t
        JOIN tool_results_fts f ON t.rowid = f.rowid
        WHERE tool_results_fts MATCH :query
        ORDER BY bm25(tool_results_fts)
        LIMIT :limit
    """)
    suspend fun searchToolResultsFts(query: String, limit: Int): List<ToolResultEntity>

    @Query("SELECT * FROM tool_results WHERE result_text LIKE '%' || :query || '%' OR tool_name LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchToolResultsLike(query: String): List<ToolResultEntity>

    @SkipQueryVerification
    @Query("""
        SELECT t.*, bm25(tool_results_fts) as score FROM tool_results t
        JOIN tool_results_fts f ON t.rowid = f.rowid
        WHERE tool_results_fts MATCH :query
          AND bm25(tool_results_fts) < :threshold
        ORDER BY bm25(tool_results_fts)
        LIMIT :limit
    """)
    suspend fun searchToolResultsFtsWithScore(query: String, limit: Int, threshold: Double): List<ToolResultWithScore>

    @Query("DELETE FROM tool_results WHERE timestamp < :timestamp")
    suspend fun pruneOlderThan(timestamp: Long)

    @Query("DELETE FROM tool_results WHERE id NOT IN (SELECT id FROM tool_results ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneExcessCount(keepCount: Int)

    @Query("SELECT COUNT(*) FROM tool_results")
    suspend fun count(): Int

    @Query("DELETE FROM tool_results WHERE ttl_minutes IS NOT NULL AND (timestamp + (ttl_minutes + :gracePeriodMinutes) * 60 * 1000) < :currentTime")
    suspend fun pruneExpiredTtl(currentTime: Long, gracePeriodMinutes: Long)

    @Transaction
    suspend fun pruneToolResults(currentTime: Long) {
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        pruneOlderThan(currentTime - thirtyDaysMillis)
        val totalCount = count()
        if (totalCount > 10000) {
            pruneExcessCount(10000)
        }
        pruneExpiredTtl(currentTime, 1440L)
    }
}