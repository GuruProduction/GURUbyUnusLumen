// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An immutable recovery point for one run.
 *
 * A CHECKPOINT IS THE SESSION, NOT A TRANSCRIPT. The agent's working state lives in
 * things the conversation history does not contain: the notebook it folded its older
 * turns into, the exchange count, the question it is waiting on, the tool invocations
 * whose outcome nobody knows yet, the exact prompt and tool set it was given. A
 * transcript alone cannot bring that back, which is why storing one and calling it a
 * checkpoint would produce an agent that resumes remembering its words but not what it
 * was doing.
 *
 * [eventSequence] IS THE BOUNDARY. It names the last event this state already reflects.
 * Recovery restores the state, then reads every event above that number and applies
 * them in order. Without an exact boundary the two overlap and a tool call can be
 * applied twice.
 *
 * THE HASH IS NOT DECORATION. [stateSha256] over canonical JSON catches a state that
 * was altered or corrupted before it was restored. Restoring a damaged session and
 * failing somewhere confusing later is strictly worse than refusing at the door with a
 * reason.
 *
 * IMMUTABLE. A later checkpoint is a new row. Nothing rewrites an older one, because a
 * recovery point that can be edited is not a recovery point.
 *
 * [eventSequence] refers to an event that has already committed; [CHECKPOINT_CREATED]
 * is appended afterwards in the same transaction and names this row. That ordering is
 * why there is no circular reference between a checkpoint and the event that records it.
 */
@Entity(
    tableName = "agent_run_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [
        Index(
            value = ["run_id", "checkpoint_sequence"],
            name = "index_agent_run_checkpoints_run_id_checkpoint_sequence",
            unique = true
        ),
    ],
)
data class AgentRunCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "checkpoint_id")
    val checkpointId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    /** One-based, incremented per committed checkpoint. */
    @ColumnInfo(name = "checkpoint_sequence")
    val checkpointSequence: Long,

    /** The highest event sequence this state already contains. Recovery replays above it. */
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,

    @ColumnInfo(name = "checkpoint_schema_version", defaultValue = "1")
    val checkpointSchemaVersion: Int = 1,

    /** Why this one exists. A checkpoint you cannot explain is a checkpoint nobody trusts. */
    @ColumnInfo(name = "checkpoint_reason")
    val checkpointReason: String,

    /** The complete state, versioned JSON. */
    @ColumnInfo(name = "state_json")
    val stateJson: String,

    /** SHA-256 over canonical `state_json`, so a tampered state is refused rather than restored. */
    @ColumnInfo(name = "state_sha256")
    val stateSha256: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        /** Why a checkpoint was taken. Stored as text so a new reason needs no migration. */
        const val REASON_RUN_CREATED = "RUN_CREATED"
        const val REASON_ROUND_BOUNDARY = "ROUND_BOUNDARY"
        const val REASON_ENTERING_WAITING = "ENTERING_WAITING"
        const val REASON_QUESTION_ANSWERED = "QUESTION_ANSWERED"
        const val REASON_SESSION_COMPACTED = "SESSION_COMPACTED"
        const val REASON_DELIBERATE_PAUSE = "DELIBERATE_PAUSE"
        const val REASON_GRACEFUL_SHUTDOWN = "GRACEFUL_SHUTDOWN"
        const val REASON_AFTER_RESTORE = "AFTER_RESTORE"
    }
}
