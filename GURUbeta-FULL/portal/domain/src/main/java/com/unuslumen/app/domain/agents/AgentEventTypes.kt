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

    /**
     * A person asked for this run to stop for now.
     *
     * SEPARATE FROM THE PAUSE ITSELF, AND THE SEPARATION IS THE POINT. Pausing is cooperative: the
     * loop can only stop safely at a round boundary, so between the request and the effect there is
     * a window in which a person has asked and the agent is still finishing its current round. That
     * window is a real fact and a surface showing "Paused" during it would be lying. The transition
     * itself is the `RUN_STATE_CHANGED` that follows, which is why this carries no state of its own.
     */
    const val RUN_PAUSE_REQUESTED = "RUN_PAUSE_REQUESTED"
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

    // ---- plans ----
    /**
     * A run's plan was created or revised.
     *
     * WHY THIS IS AN EVENT AND NOT ONLY A NOTE. The planning tools write notes whose steps are lines
     * with checkboxes: there is no step identity, and updating one destroys what it said before. A
     * revision history cannot be reconstructed from a document that overwrites itself, so the ledger
     * becomes the history and each revision is an appended fact.
     *
     * THE REVISION NUMBER IS PER RUN, not per plan. A run's plan evolves in one line, and numbering
     * from one makes "the third plan this run produced" answerable without a join.
     */
    const val PLAN_REVISED = "PLAN_REVISED"

    // ---- artefacts ----
    /**
     * A run produced a persistent output.
     *
     * ITS OWN EVENT BECAUSE IT IS ITS OWN FACT. Without it, recording an artefact would have had to
     * borrow a tool event, which would say a tool ran when what actually happened is that the agent
     * wrote something. The artefact table holds the provenance; this is the point in the run's
     * history where the output appeared.
     */
    const val ARTEFACT_CREATED = "ARTEFACT_CREATED"

    // ---- decisions ----
    /**
     * The agent recorded a decision, with its reasons.
     *
     * WHY THIS IS NOT THE REASONING CHANNEL. Reasoning is whatever the provider chose to emit, in
     * whatever shape it emits it, and a model without a reasoning channel produces none at all. A
     * decision record is a fact the agent deliberately wrote: the problem as it understood it, the
     * approach it took, what it considered and rejected, why it chose what it chose, what evidence
     * it had, where it is still uncertain, and what it will do next. Those are different kinds of
     * information and the specification requires them labelled as such.
     *
     * IT IS ALSO THE ONLY ONE OF THE TWO THAT WORKS ON EVERY MODEL. A structured decision can be
     * recorded by any agent that calls the tool, so a BYO model with no reasoning channel still
     * produces an explanation of its thinking rather than an empty panel.
     */
    const val DECISION_RECORDED = "DECISION_RECORDED"

    // ---- verification ----
    /**
     * The agent recorded a check it made, and how far that check got.
     *
     * WHY IT IS NOT A DECISION. A decision records a fork in the work; this records whether something
     * the work produced is true. Section 4.4 asks for a completion claim to be supported by execution
     * evidence, and that needs a place to put the evidence, the level of confirmation and what
     * remains open. A report is prose; this is a record a reader can check against the run.
     */
    const val VERIFICATION_RECORDED = "VERIFICATION_RECORDED"

    // ---- checkpoints ----
    const val CHECKPOINT_CREATED = "CHECKPOINT_CREATED"

    /** Every name, so a test can prove the writer and the reducer have not drifted. */
    val ALL: List<String> = listOf(
        RUN_CREATED, RUN_STATE_CHANGED, RUN_CONFIGURATION_CHANGED, ASSIGNMENT_AMENDED,
        RUN_CANCEL_REQUESTED, RUN_PAUSE_REQUESTED, RUN_LEASE_ACQUIRED, RUN_LEASE_RELEASED,
        SESSION_CREATED, SESSION_RESTORED, SESSION_COMPACTED,
        MODEL_REQUEST_STARTED, MODEL_OUTPUT_BATCH, MODEL_TEXT_COMPLETED,
        MODEL_REASONING_COMPLETED, MODEL_TOOL_CALL_PROPOSED, MODEL_STREAM_ENDED,
        MODEL_REQUEST_COMPLETED, MODEL_REQUEST_FAILED, MODEL_REQUEST_INTERRUPTED,
        TOOL_PERMISSION_GRANTED, TOOL_PERMISSION_DENIED, TOOL_CALL_STARTED, TOOL_PROGRESS,
        TOOL_CALL_COMPLETED, TOOL_CALL_FAILED, TOOL_CALL_REFUSED, TOOL_CALL_OUTCOME_UNKNOWN,
        MESSAGE_CREATED, MESSAGE_DELIVERED, MESSAGE_DELIVERY_FAILED, QUESTION_OPENED,
        QUESTION_ANSWERED, PLAN_REVISED, ARTEFACT_CREATED, DECISION_RECORDED,
        VERIFICATION_RECORDED, CHECKPOINT_CREATED,
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
        RUN_PAUSE_REQUESTED to RestorePolicy.NoSessionEffect(
            "A pause request is an instruction about the run. The pause itself is the state change " +
                "that follows and the checkpoint taken with it, which is what a resume restores. " +
                "Re-applying the request during a replay would pause a run that had already been " +
                "resumed, which is the defect this policy prevents."
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
        /*
         * MESSAGES NOW HAVE A PRODUCER, SO THEY HAVE A POLICY.
         *
         * These three were classified `LaterPhase` while nothing emitted them. Phase 5's broker
         * writes them, so the classification changes from "no producer yet" to a decision about
         * what a rebuilt session needs.
         *
         * A MESSAGE DOES NOT REBUILD THE SESSION. The conversation is its own table with its own
         * ordering, and a resume rebuilds the model's context from the checkpoint's message list
         * rather than from the ledger's record of who said what. What the events provide is the
         * evidence that an exchange happened at a point in the run, which is a different question
         * from what the model was holding.
         */
        MESSAGE_CREATED to RestorePolicy.NoSessionEffect(
            "The agent_messages table holds the conversation with its own ordering. A resumed " +
                "session rebuilds its context from the checkpoint's message list, and the event is " +
                "the ledger's record that an exchange happened."
        ),
        MESSAGE_DELIVERED to RestorePolicy.NoSessionEffect(
            "Delivery is a fact about the message row, which carries the same state. Rebuilding a " +
                "session from a delivery marker would be reconstructing a conversation from its " +
                "postal history."
        ),
        MESSAGE_DELIVERY_FAILED to RestorePolicy.NoSessionEffect(
            "A message that never arrived is marked FAILED on its own row, so it cannot appear in " +
                "the rebuilt conversation as though it had. The event is evidence, not input."
        ),
        QUESTION_OPENED to RestorePolicy.Rebuilds,
        QUESTION_ANSWERED to RestorePolicy.Rebuilds,

        // ---- plans ----
        PLAN_REVISED to RestorePolicy.NoSessionEffect(
            "A plan revision is a record of what the agent intended, kept for the room to show and " +
                "for a reader to see how the approach changed. It is not rebuilt into the session, " +
                "because the session's context is the checkpoint and a plan is not conversation."
        ),

        // ---- artefacts ----
        ARTEFACT_CREATED to RestorePolicy.NoSessionEffect(
            "An artefact is a durable output in its own table with its own provenance. A resumed " +
                "session does not hold artefacts in its message list, and rebuilding them from " +
                "their announcement would be inventing content from a title."
        ),

        // ---- decisions ----
        DECISION_RECORDED to RestorePolicy.NoSessionEffect(
            "A decision is a record the agent wrote about its own reasoning, kept for the room to " +
                "show. The agent's working context is the checkpoint, and replaying decisions into " +
                "it would tell the model it had already deliberated again."
        ),

        // ---- verification ----
        VERIFICATION_RECORDED to RestorePolicy.NoSessionEffect(
            "A check the agent made about its own output, kept for a reader to weigh rather than " +
                "rebuilt into the working context. Replaying it would tell the model it had already " +
                "checked something on the current attempt, which may not be true after a resume."
        ),

        // ---- checkpoints ----
        CHECKPOINT_CREATED to RestorePolicy.NoSessionEffect(
            "The checkpoint's own contents are what recovery restores. The event that announces " +
                "it is the ledger's record that one was taken, not session content."
        ),
    )
}
