// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * The persisted conversations between participants in a run.
 *
 * THE TIMELINE IS THE PRIMARY READ. Everything else here exists to answer a narrower question that
 * the timeline would answer by scanning: what is still queued, what is waiting for an answer, what
 * a specific participant said.
 */
@Dao
interface AgentMessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: AgentMessageEntity)

    /** The whole conversation for one run, in order. What the Communications area draws. */
    @Query("SELECT * FROM agent_messages WHERE run_id = :runId ORDER BY sequence_number ASC")
    suspend fun messagesForRun(runId: String): List<AgentMessageEntity>

    /** The same as a stream, so a room can show a run's conversation updating live. */
    @Query("SELECT * FROM agent_messages WHERE run_id = :runId ORDER BY sequence_number ASC")
    fun messagesForRunFlow(runId: String): Flow<List<AgentMessageEntity>>

    /** The newest message in a run, for allocating the next sequence number. */
    @Query("SELECT * FROM agent_messages WHERE run_id = :runId ORDER BY sequence_number DESC LIMIT 1")
    suspend fun lastMessage(runId: String): AgentMessageEntity?

    /**
     * Everything addressed to one participant and not yet processed.
     *
     * THIS IS HOW A RUN LEARNS WHAT IT HAS BEEN TOLD. A message the user sent while a tool was
     * running is queued; the loop picks it up at its next safe boundary and nothing is lost.
     */
    @Query(
        "SELECT * FROM agent_messages WHERE run_id = :runId AND recipient_type = :recipient " +
            "AND delivery_state IN ('QUEUED', 'DELIVERED') ORDER BY sequence_number ASC"
    )
    suspend fun pendingFor(runId: String, recipient: String): List<AgentMessageEntity>

    /** The question a run is waiting on, if any. At most one is outstanding by construction. */
    @Query(
        "SELECT * FROM agent_messages WHERE run_id = :runId AND message_type = 'QUESTION' " +
            "ORDER BY sequence_number DESC LIMIT 1"
    )
    suspend fun latestQuestion(runId: String): AgentMessageEntity?

    /** Every message in one reply chain, so a question and its answer read together. */
    @Query(
        "SELECT * FROM agent_messages WHERE correlation_id = :correlationId " +
            "ORDER BY sequence_number ASC"
    )
    suspend fun thread(correlationId: String): List<AgentMessageEntity>

    /** Move a message along its delivery path. One statement, so there is no gap between the two. */
    @Query(
        "UPDATE agent_messages SET delivery_state = :state, delivered_at = :at " +
            "WHERE message_id = :messageId"
    )
    suspend fun markDelivered(messageId: String, state: String, at: Long)

    /** Record that a message actually reached the model's context. */
    @Query("UPDATE agent_messages SET delivery_state = 'PROCESSED', consumed_at = :at WHERE message_id = :messageId")
    suspend fun markProcessed(messageId: String, at: Long)

    /** A delivery that could not happen, with the reason. Never silently dropped. */
    @Query(
        "UPDATE agent_messages SET delivery_state = 'FAILED', delivery_failure_reason = :reason " +
            "WHERE message_id = :messageId"
    )
    suspend fun markFailed(messageId: String, reason: String)

    /**
     * A whole conversation's transcript, across every run in it.
     *
     * WHY THIS IS NOT `messagesForRun`. A conversation outlives the run that started it: a person
     * sends, the run finishes, they send again, and a second run begins in the same conversation. The
     * room showed one run's messages, so the transcript emptied the moment a new run started, which
     * is the "old messages disappear" defect. Joining through `agent_runs` gives the conversation,
     * which is what the person is actually having.
     *
     * ORDERED BY TIME WITH A DETERMINISTIC TIE-BREAKER. Two runs in one conversation can interleave,
     * and ordering by run-then-sequence would put all of one run's messages before all of the
     * other's regardless of when they were said. `created_at` is the true order; `message_id` breaks
     * a tie so the list cannot reorder itself between two reads of the same rows.
     */
    @Query(
        """
        SELECT m.* FROM agent_messages m
        INNER JOIN agent_runs r ON r.run_id = m.run_id
        WHERE r.conversation_id = :conversationId
        ORDER BY m.created_at ASC, m.message_id ASC
        """
    )
    suspend fun messagesForConversation(conversationId: String): List<AgentMessageEntity>

    /** How many messages a run has, for a counter without loading the rows. */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE run_id = :runId")
    suspend fun countForRun(runId: String): Int

    /** One run's conversation goes with the run, which goes with its conversation. Cascade handles it. */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE run_id = :runId AND author_type = :authorType")
    suspend fun countByAuthor(runId: String, authorType: String): Int
}
