// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.MemoryInjectionEntity

@Dao
interface MemoryInjectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MemoryInjectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MemoryInjectionEntity)

    /** Every injection recorded for one conversation, oldest first. */
    @Query("SELECT * FROM memory_injections WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun forConversation(conversationId: String): List<MemoryInjectionEntity>

    /** Recent injections across all conversations, newest first. */
    @Query("SELECT * FROM memory_injections ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryInjectionEntity>

    /** Was this memory injected in this conversation at all? */
    @Query("SELECT COUNT(*) FROM memory_injections WHERE conversation_id = :conversationId AND memory_id = :memoryId")
    suspend fun countFor(conversationId: String, memoryId: String): Int

    /** Mark a previously injected memory as confirmed used. */
    @Query("UPDATE memory_injections SET confirmed_used_at = :whenMillis WHERE conversation_id = :conversationId AND memory_id = :memoryId")
    suspend fun markConfirmedUsed(conversationId: String, memoryId: String, whenMillis: Long)

    /**
     * Record a recall failure: the user restated content the preamble did not
     * inject. Creates a row with lane "restated" so the set of these has the same
     * shape as a normal injection and can be counted the same way.
     */
    @Query("SELECT COUNT(*) FROM memory_injections WHERE conversation_id = :conversationId AND memory_id = :memoryId AND restated_without_injection = 1")
    suspend fun countRestated(conversationId: String, memoryId: String): Int

    /**
     * The measurement this whole table exists for.
     *
     * Per query, the fraction of injected memories that later proved useful
     * (confirmed_used_at set) and the count that never were. A lane whose
     * precision is low is injecting noise into the model's context even when
     * recall looks acceptable.
     */
    @Query("""
        SELECT lane,
               COUNT(*) AS injected,
               SUM(CASE WHEN confirmed_used_at IS NOT NULL THEN 1 ELSE 0 END) AS used,
               AVG(rank_in_lane) AS avg_rank
        FROM memory_injections
        WHERE timestamp >= :sinceMillis
        GROUP BY lane
    """)
    suspend fun precisionByLane(sinceMillis: Long): List<LanePrecision>

    /** Recall failures per day: restatements the preamble should have caught. */
    @Query("""
        SELECT COUNT(*) FROM memory_injections
        WHERE restated_without_injection = 1 AND timestamp >= :sinceMillis
    """)
    suspend fun recallFailuresSince(sinceMillis: Long): Int

    /** Prune the ledger on the same horizon as everything else. */
    @Query("DELETE FROM memory_injections WHERE timestamp < :timestamp")
    suspend fun pruneOlderThan(timestamp: Long)

    @Query("DELETE FROM memory_injections WHERE id NOT IN (SELECT id FROM memory_injections ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneExcessCount(keepCount: Int)

    @Query("SELECT COUNT(*) FROM memory_injections")
    suspend fun count(): Int

    suspend fun pruneLedger(currentTime: Long) {
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        pruneOlderThan(currentTime - thirtyDaysMillis)
        if (count() > 20000) {
            pruneExcessCount(20000)
        }
    }
}

/** Aggregate row for one lane, used to compare retrieval lanes on real data. */
data class LanePrecision(
    val lane: String,
    val injected: Int,
    val used: Int,
    val avgRank: Double?
) {
    val precision: Double get() = if (injected == 0) 0.0 else used.toDouble() / injected
}
