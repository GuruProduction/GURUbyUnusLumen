// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * The event names replay understands, in one place.
 *
 * WHY THESE LIVE IN THE DOMAIN RATHER THAN ON THE ENTITY. The reducer is pure and cannot
 * see the database, but it still has to know which strings mean what. Keeping the names
 * here means the reducer and the writer agree by construction, and a test can assert the
 * two lists are identical rather than discovering a typo at runtime when an event silently
 * falls through.
 *
 * A NEW TYPE NEEDS NO COLUMN AND NO MIGRATION. It needs a name here, a payload shape, and
 * a branch in [AgentRunReplay] if it changes the rebuilt session.
 */
object AgentEventTypes {

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

    /** Every name, so a test can prove the writer and the reducer have not drifted. */
    val ALL: List<String> = listOf(
        RUN_CREATED, RUN_STATE_CHANGED, RUN_CONFIGURATION_CHANGED, ASSIGNMENT_AMENDED,
        RUN_CANCEL_REQUESTED, RUN_LEASE_ACQUIRED, RUN_LEASE_RELEASED, SESSION_CREATED,
        SESSION_RESTORED, SESSION_COMPACTED,
        MODEL_REQUEST_STARTED, MODEL_OUTPUT_BATCH, MODEL_TEXT_COMPLETED,
        MODEL_REASONING_COMPLETED, MODEL_TOOL_CALL_PROPOSED, MODEL_STREAM_ENDED,
        MODEL_REQUEST_COMPLETED, MODEL_REQUEST_FAILED, MODEL_REQUEST_INTERRUPTED,
        TOOL_PERMISSION_GRANTED, TOOL_PERMISSION_DENIED, TOOL_CALL_STARTED, TOOL_PROGRESS,
        TOOL_CALL_COMPLETED, TOOL_CALL_FAILED, TOOL_CALL_REFUSED, TOOL_CALL_OUTCOME_UNKNOWN,
        MESSAGE_CREATED, MESSAGE_DELIVERED, MESSAGE_DELIVERY_FAILED, QUESTION_OPENED,
        QUESTION_ANSWERED, CHECKPOINT_CREATED,
    )
}
