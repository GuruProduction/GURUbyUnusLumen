// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentRunEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * The append-only ledger.
 *
 * THERE IS NO UPDATE AND NO DELETE. That is the whole contract. An event that is wrong
 * is corrected by a later event that says what it corrects; the original stays visible,
 * because history that can be tidied is history that cannot be trusted.
 *
 * SEQUENCE ALLOCATION IS NOT HERE, BY DESIGN. Allocating the next number is a
 * read-modify-write against `agent_runs.last_event_sequence` and must happen inside one
 * transaction with this insert; putting it in a DAO method on its own would tempt a
 * caller into doing the read outside the transaction, which is exactly the race the
 * unique index exists to catch.
 */
@Dao
interface AgentRunEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AgentRunEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicate(event: AgentRunEventEntity): Long

    @Query("SELECT * FROM agent_run_events WHERE event_id = :eventId")
    suspend fun event(eventId: String): AgentRunEventEntity?

    /** The whole run in order. The order of this list is the order things happened. */
    @Query("SELECT * FROM agent_run_events WHERE run_id = :runId ORDER BY sequence_number ASC")
    suspend fun eventsForRun(runId: String): List<AgentRunEventEntity>

    @Query("SELECT * FROM agent_run_events WHERE run_id = :runId ORDER BY sequence_number ASC")
    fun observeEventsForRun(runId: String): Flow<List<AgentRunEventEntity>>

    /** Everything after a checkpoint boundary. This is what replay applies. */
    @Query(
        """
        SELECT * FROM agent_run_events
        WHERE run_id = :runId AND sequence_number > :afterSequence
        ORDER BY sequence_number ASC
        """
    )
    suspend fun eventsAfter(runId: String, afterSequence: Long): List<AgentRunEventEntity>

    @Query(
        """
        SELECT * FROM agent_run_events
        WHERE run_id = :runId AND event_type = :eventType
        ORDER BY sequence_number ASC
        """
    )
    suspend fun eventsOfType(runId: String, eventType: String): List<AgentRunEventEntity>

    /** One interaction end to end: a request, its batches, its proposals and its completion. */
    @Query(
        """
        SELECT * FROM agent_run_events
        WHERE run_id = :runId AND correlation_id = :correlationId
        ORDER BY sequence_number ASC
        """
    )
    suspend fun eventsByCorrelation(runId: String, correlationId: String): List<AgentRunEventEntity>

    @Query("SELECT * FROM agent_run_events WHERE run_id = :runId AND idempotency_key = :key LIMIT 1")
    suspend fun eventByKey(runId: String, key: String): AgentRunEventEntity?

    @Query("SELECT MAX(sequence_number) FROM agent_run_events WHERE run_id = :runId")
    suspend fun highestSequence(runId: String): Long?

    @Query("SELECT COUNT(*) FROM agent_run_events WHERE run_id = :runId")
    suspend fun countForRun(runId: String): Int

    /**
     * How many events of one type a run has.
     *
     * A COUNT RATHER THAN A LOAD. The history list shows a tool-call count per finished run, and it
     * used to get it by reading every event of all twenty listed runs and counting in Kotlin. That is
     * twenty full table scans per refresh of a screen that changes nothing about those runs, which is
     * the whole cost with none of the information.
     */
    @Query(
        "SELECT COUNT(*) FROM agent_run_events WHERE run_id = :runId AND event_type = :eventType"
    )
    suspend fun countOfType(runId: String, eventType: String): Int
}
