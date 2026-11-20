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
 * One fact about what actually happened during a run.
 *
 * APPEND ONLY. There is no update path and no delete path in the repository, and the
 * only way history changes is by a compensating event that says what it is correcting.
 * A ledger somebody can edit is not evidence, it is a story.
 *
 * THE SEQUENCE IS THE SPINE. [sequenceNumber] starts at 1 and rises by one per event
 * within its run, allocated inside the same transaction that inserts the row and
 * advances `agent_runs.last_event_sequence`. The unique index on the pair is the last
 * line of defence: two writers that somehow both allocate four cannot both commit.
 *
 * THE PAYLOAD IS DATA, NOT PROSE. A tool event carries the tool's name, its arguments
 * and its result. It never carries a sentence claiming that a tool was used, because a
 * generated description of an action is not the action and an audit trail built from
 * model prose is an audit trail of the model's self-image.
 *
 * [idempotencyKey] exists because a retried write must not become a second fact. The
 * unique index tolerates many NULLs, so events that genuinely have no natural key are
 * unconstrained while the ones that do are protected.
 */
@Entity(
    tableName = "agent_run_events",
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
            value = ["run_id", "sequence_number"],
            name = "index_agent_run_events_run_id_sequence_number",
            unique = true
        ),
        Index(
            value = ["run_id", "event_type", "sequence_number"],
            name = "index_agent_run_events_run_id_event_type_sequence_number"
        ),
        Index(
            value = ["run_id", "correlation_id"],
            name = "index_agent_run_events_run_id_correlation_id"
        ),
        Index(
            value = ["run_id", "idempotency_key"],
            name = "index_agent_run_events_run_id_idempotency_key",
            unique = true
        ),
    ],
)
data class AgentRunEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    /** One-based position within this run. Allocated transactionally; never reused. */
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Long,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "actor_type")
    val actorType: String,

    /** Which agent, tool, model or process. Null when the actor type is enough on its own. */
    @ColumnInfo(name = "actor_id")
    val actorId: String? = null,

    /** Ties a request, its batches, its proposals and its completion together. */
    @ColumnInfo(name = "correlation_id")
    val correlationId: String? = null,

    @ColumnInfo(name = "causation_event_id")
    val causationEventId: String? = null,

    /** Stops a retried write producing a second copy of the same fact. */
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String? = null,

    /** When the thing happened. */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    /** When it landed on disk. The two differ when a batch was buffered. */
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,

    /** The shape of [payloadJson], so a later build can read an older event without guessing. */
    @ColumnInfo(name = "payload_schema_version", defaultValue = "1")
    val payloadSchemaVersion: Int = 1,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
) {
    companion object {
        // ---- actors ----
        const val ACTOR_USER = "USER"
        const val ACTOR_GURU = "GURU"
        const val ACTOR_AGENT = "AGENT"
        const val ACTOR_MODEL = "MODEL"
        const val ACTOR_TOOL = "TOOL"
        const val ACTOR_RUNTIME = "RUNTIME"
        const val ACTOR_SYSTEM = "SYSTEM"

        // ---- run lifecycle ----
        const val RUN_CREATED = "RUN_CREATED"
        const val RUN_STATE_CHANGED = "RUN_STATE_CHANGED"
        const val RUN_CONFIGURATION_CHANGED = "RUN_CONFIGURATION_CHANGED"
        const val ASSIGNMENT_AMENDED = "ASSIGNMENT_AMENDED"
        const val RUN_CANCEL_REQUESTED = "RUN_CANCEL_REQUESTED"
        const val RUN_LEASE_ACQUIRED = "RUN_LEASE_ACQUIRED"
        const val RUN_LEASE_RELEASED = "RUN_LEASE_RELEASED"
        const val SESSION_CREATED = "SESSION_CREATED"
        const val SESSION_RESTORED = "SESSION_RESTORED"
        const val SESSION_COMPACTED = "SESSION_COMPACTED"

        // ---- model ----
        const val MODEL_REQUEST_STARTED = "MODEL_REQUEST_STARTED"
        const val MODEL_OUTPUT_BATCH = "MODEL_OUTPUT_BATCH"
        const val MODEL_TEXT_COMPLETED = "MODEL_TEXT_COMPLETED"
        const val MODEL_REASONING_COMPLETED = "MODEL_REASONING_COMPLETED"
        const val MODEL_TOOL_CALL_PROPOSED = "MODEL_TOOL_CALL_PROPOSED"
        const val MODEL_STREAM_ENDED = "MODEL_STREAM_ENDED"
        const val MODEL_REQUEST_COMPLETED = "MODEL_REQUEST_COMPLETED"
        const val MODEL_REQUEST_FAILED = "MODEL_REQUEST_FAILED"
        const val MODEL_REQUEST_INTERRUPTED = "MODEL_REQUEST_INTERRUPTED"

        // ---- tools ----
        const val TOOL_PERMISSION_GRANTED = "TOOL_PERMISSION_GRANTED"
        const val TOOL_PERMISSION_DENIED = "TOOL_PERMISSION_DENIED"
        const val TOOL_CALL_STARTED = "TOOL_CALL_STARTED"
        const val TOOL_PROGRESS = "TOOL_PROGRESS"
        const val TOOL_CALL_COMPLETED = "TOOL_CALL_COMPLETED"
        const val TOOL_CALL_FAILED = "TOOL_CALL_FAILED"
        const val TOOL_CALL_REFUSED = "TOOL_CALL_REFUSED"
        const val TOOL_CALL_OUTCOME_UNKNOWN = "TOOL_CALL_OUTCOME_UNKNOWN"

        // ---- communication ----
        const val MESSAGE_CREATED = "MESSAGE_CREATED"
        const val MESSAGE_DELIVERED = "MESSAGE_DELIVERED"
        const val MESSAGE_DELIVERY_FAILED = "MESSAGE_DELIVERY_FAILED"
        const val QUESTION_OPENED = "QUESTION_OPENED"
        const val QUESTION_ANSWERED = "QUESTION_ANSWERED"

        // ---- checkpoints ----
        const val CHECKPOINT_CREATED = "CHECKPOINT_CREATED"

        /** The destination stream a message belongs to, so recovery never conflates the two. */
        const val STREAM_AGENT_ROOM = "AGENT_ROOM"
        const val STREAM_MODEL_CONTEXT = "MODEL_CONTEXT"
    }
}
