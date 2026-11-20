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

    /**
     * What a reducer must do with a type, decided once for every name in [ALL].
     *
     * WHY THIS IS A CLASSIFICATION RATHER THAN A CONVENTION. `AgentRunReplay` branches on a
     * subset of these names. Every other name fell through its `else` and vanished on
     * restore, silently, which is the one failure a ledger exists to prevent: a run rebuilt
     * from its own history that is missing the facts somebody added later. A test asserts
     * that the keys of [RESTORE_POLICY] are exactly [ALL], so a new name cannot be added
     * without an author deciding what it means to recovery.
     *
     * THE THREE POLICIES ARE DIFFERENT AND NONE IS A DEFAULT.
     *
     *  - [Rebuilds] means the reducer has a branch and the event changes the reconstructed
     *    session: messages, tool calls, the pending question, the notebook, the counters.
     *  - [NoSessionEffect] means the reducer deliberately ignores it BECAUSE it cannot change
     *    what a session looks like when rebuilt. A lease heartbeat is operational state; a
     *    permission decision is evidence about a call that is already represented by its own
     *    start and completion. Each one below says why, so "no branch" is a decision rather
     *    than an omission.
     *  - [LaterPhase] means the type belongs to a phase that has not been built, and the
     *    reducer will need a branch when its producer exists. Declaring that now is what stops
     *    a future phase adding a producer and forgetting the reader.
     */
    sealed interface RestorePolicy {
        /** The reducer rebuilds session state from this event. */
        data object Rebuilds : RestorePolicy

        /** Deliberately has no reducer branch, and the reason is its own field. */
        data class NoSessionEffect(val reason: String) : RestorePolicy

        /** Declared for a later phase; the producer does not exist yet. */
        data class LaterPhase(val reason: String) : RestorePolicy
    }

    /**
     * One policy per declared name. [ALL] and this map must name the same set.
     *
     * Kept here rather than in the reducer because the reducer can only see the types it
     * branches on, and the whole point is that a type it does not branch on is accounted
     * for. Adding a name to [ALL] without adding it here fails the test in
     * AgentEventCoverageTest, which is the gate that keeps this honest.
     */
    val RESTORE_POLICY: Map<String, RestorePolicy> = mapOf(
        // ---- run lifecycle ----
        RUN_CREATED to RestorePolicy.NoSessionEffect(
            "The run row already carries the assignment and both configuration snapshots, so " +
                "replay starts from the row rather than rebuilding it from this event."
        ),
        RUN_STATE_CHANGED to RestorePolicy.NoSessionEffect(
            "The materialised status is what recovery reads. Rebuilding status from the event " +
                "stream would give two answers when a transition was interrupted mid-write."
        ),
        RUN_CONFIGURATION_CHANGED to RestorePolicy.NoSessionEffect(
            "A configuration change is evidence about the run, not part of the rebuilt session. " +
                "The session's prompt and tool set are restored from the checkpoint that was " +
                "taken after the change, which is the state that actually ran."
        ),
        ASSIGNMENT_AMENDED to RestorePolicy.LaterPhase(
            "Phase 5 adds assignment amendments. When a producer exists, replay must apply them " +
                "in order so a resumed agent sees every instruction it was ever given."
        ),
        RUN_CANCEL_REQUESTED to RestorePolicy.NoSessionEffect(
            "A cancellation request is an instruction about the run, and whether it was honoured " +
                "is recorded by the state change that follows. The session is not rebuilt from it."
        ),
        RUN_LEASE_ACQUIRED to RestorePolicy.NoSessionEffect(
            "Operational liveness metadata. It says who held the run, not what the session contained."
        ),
        RUN_LEASE_RELEASED to RestorePolicy.NoSessionEffect(
            "Operational liveness metadata, for the same reason as acquisition."
        ),
        SESSION_CREATED to RestorePolicy.NoSessionEffect(
            "The session that existed is either still in memory or is reconstructed from its " +
                "checkpoint. This event is the record that one began, not its contents."
        ),
        SESSION_RESTORED to RestorePolicy.NoSessionEffect(
            "The restore is proved by the checkpoint it names and by the events replayed after it. " +
                "Re-applying it during a replay of the same run would restore twice."
        ),
        SESSION_COMPACTED to RestorePolicy.Rebuilds,

        // ---- model ----
        MODEL_REQUEST_STARTED to RestorePolicy.Rebuilds,
        MODEL_OUTPUT_BATCH to RestorePolicy.Rebuilds,
        MODEL_TEXT_COMPLETED to RestorePolicy.Rebuilds,
        MODEL_REASONING_COMPLETED to RestorePolicy.Rebuilds,
        MODEL_TOOL_CALL_PROPOSED to RestorePolicy.Rebuilds,
        MODEL_STREAM_ENDED to RestorePolicy.NoSessionEffect(
            "The completion event that follows carries the canonical text, and the batches carry " +
                "the frames. A stream-end marker adds counts, not content."
        ),
        MODEL_REQUEST_COMPLETED to RestorePolicy.Rebuilds,
        MODEL_REQUEST_FAILED to RestorePolicy.NoSessionEffect(
            "A failed request produced no assistant turn. The run's failure is materialised on " +
                "the row, and the request's own record is evidence rather than session content."
        ),
        MODEL_REQUEST_INTERRUPTED to RestorePolicy.NoSessionEffect(
            "An interrupted request has no completed turn to rebuild. Its partial output, where " +
                "any was captured, is already in the batches before it."
        ),

        // ---- tools ----
        TOOL_PERMISSION_GRANTED to RestorePolicy.NoSessionEffect(
            "A permission decision is evidence about a call that its own start and completion " +
                "already represent in the rebuilt session. The decision belongs on the invocation " +
                "record, not in the message list."
        ),
        TOOL_PERMISSION_DENIED to RestorePolicy.NoSessionEffect(
            "A denied call never ran, so there is no invocation to rebuild. The refusal is " +
                "evidence, and the model was told about it by the refusal that follows."
        ),
        TOOL_CALL_STARTED to RestorePolicy.Rebuilds,
        TOOL_PROGRESS to RestorePolicy.NoSessionEffect(
            "Live progress is a stream of updates about work in flight. The completion carries " +
                "the outcome, and replaying progress would rebuild a ticker rather than a session."
        ),
        TOOL_CALL_COMPLETED to RestorePolicy.Rebuilds,
        TOOL_CALL_FAILED to RestorePolicy.Rebuilds,
        TOOL_CALL_REFUSED to RestorePolicy.Rebuilds,
        TOOL_CALL_OUTCOME_UNKNOWN to RestorePolicy.Rebuilds,

        // ---- communication ----
        MESSAGE_CREATED to RestorePolicy.Rebuilds,
        MESSAGE_DELIVERED to RestorePolicy.LaterPhase(
            "Phase 5 adds delivery state. When a producer exists, replay must decide whether a " +
                "delivered message enters the model context and an undelivered one does not."
        ),
        MESSAGE_DELIVERY_FAILED to RestorePolicy.LaterPhase(
            "Phase 5 adds delivery failure. A message that never arrived must not appear in the " +
                "rebuilt conversation as though it had, so this needs its own branch then."
        ),
        QUESTION_OPENED to RestorePolicy.Rebuilds,
        QUESTION_ANSWERED to RestorePolicy.Rebuilds,

        // ---- checkpoints ----
        CHECKPOINT_CREATED to RestorePolicy.NoSessionEffect(
            "The checkpoint's own contents are what recovery restores. The event that announces " +
                "it is the ledger's record that one was taken, not session content."
        ),
    )
}
