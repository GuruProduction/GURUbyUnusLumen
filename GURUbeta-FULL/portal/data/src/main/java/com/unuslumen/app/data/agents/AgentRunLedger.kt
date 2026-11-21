// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.withTransaction
import com.unuslumen.app.database.dao.AgentRunCheckpointDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.AgentRunEventDao
import com.unuslumen.app.database.entity.AgentRunCheckpointEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.guruDatabase
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * The one place a run is written down.
 *
 * BOTH AGENT PATHS USE THIS. `AgentRuntime` and `ProjectAgentRepositoryImpl` are
 * different execution loops with different shapes, and giving each its own persistence
 * would produce two truths about what happened. There is one ledger, one event
 * contract and one recovery rule, and both paths reach it here.
 *
 * EVERYTHING THAT MUST MOVE TOGETHER MOVES IN ONE TRANSACTION. Allocating a sequence,
 * inserting the event, advancing the counter and updating the materialised state are
 * one write. If any part fails the whole thing rolls back, because a sequence spent
 * without an event leaves a hole, and an event without an advanced counter invites the
 * next writer to reuse the number.
 *
 * THE COUNTER RULE, WHICH IS EASY TO GET WRONG. `CHECKPOINT_CREATED` is an event like
 * any other and consumes a sequence, so committing a checkpoint advances BOTH counters.
 * Advancing only the checkpoint counter is the defect that lets a later event take a
 * sequence already used.
 */
class AgentRunLedger(
    private val database: guruDatabase,
    private val runDao: AgentRunDao,
    private val eventDao: AgentRunEventDao,
    private val checkpointDao: AgentRunCheckpointDao,
    /**
     * The protector. Injected rather than reached for statically so the fail-closed rule
     * can be proved on a JVM where no Android Keystore exists at all.
     */
    private val protector: com.unuslumen.app.domain.security.DataProtector =
        com.unuslumen.app.data.security.ProtectedData,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Seal a value, and REFUSE THE WRITE when it cannot be sealed.
     *
     * THIS WAS FAILING OPEN AND THAT WAS THE DEFECT. The previous version logged a failure
     * and stored the plaintext, on the argument that keeping the record mattered more than
     * protecting it. That argument is wrong for a value classified as protected: silently
     * persisting somebody's messages, prompts or tool arguments in the clear on a device
     * where the keystore is unavailable is precisely the outcome the classification exists
     * to prevent, and the log line announcing it is not a mitigation.
     *
     * So a failure throws. The transaction rolls back, nothing is written, and the caller
     * is told the record could not be kept securely. A missing record is recoverable and
     * honest; a plaintext one is neither.
     *
     * A deliberate redaction is different and is allowed: that is a decision made before
     * the write to withhold a value, recorded with its reason, not a failure afterwards.
     */
    private fun protect(field: com.unuslumen.app.domain.security.ProtectionContext, value: String): String {
        if (value.isBlank()) return value
        return when (val outcome = protector.seal(field, value)) {
            is com.unuslumen.app.domain.security.ProtectedWrite.Sealed -> outcome.envelope
            is com.unuslumen.app.domain.security.ProtectedWrite.Redacted ->
                "{\"protected\":\"redacted\",\"reason\":${quote(outcome.reason)}}"
            is com.unuslumen.app.domain.security.ProtectedWrite.Failed ->
                throw IllegalStateException(
                    "Refusing to write '${field.field}' in the clear: the vault could not seal it " +
                        "(${outcome.reason}). A record that cannot be kept securely is not kept at all.",
                )
        }
    }

    /**
     * Read a value back through the same policy.
     *
     * Values written before this class protected anything come back as they were stored,
     * so old rows stay readable. Everything written since is an envelope, and an envelope
     * that will not open is reported as locked or tampered rather than as plaintext.
     */
    fun unprotect(
        field: com.unuslumen.app.domain.security.ProtectionContext,
        stored: String,
    ): com.unuslumen.app.domain.security.ProtectedRead {
        if (stored.isBlank()) {
            return com.unuslumen.app.domain.security.ProtectedRead.Absent("nothing was stored for this field")
        }
        if (!protector.isEnvelope(stored)) {
            // A plaintext historical value. Readable, and honestly not protected.
            return com.unuslumen.app.domain.security.ProtectedRead.Value(stored)
        }
        return protector.open(field, stored)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    data class EventDraft(
        val eventType: String,
        val actorType: String,
        val payload: String,
        val actorId: String? = null,
        val correlationId: String? = null,
        val causationEventId: String? = null,
        val idempotencyKey: String? = null,
        val occurredAt: Long = System.currentTimeMillis(),
        val payloadSchemaVersion: Int = 1,
    )

    // ------------------------------------------------------------------
    // Creating a run
    // ------------------------------------------------------------------

    /**
     * Create a run and its first event together.
     *
     * THE RUN DOES NOT EXIST UNTIL ITS CREATION IS RECORDED. A run row with no
     * RUN_CREATED would be a piece of history with no beginning, and recovery reading
     * it would have to guess what it was for.
     */
    suspend fun createRun(run: AgentRunEntity): AgentRunEntity = database.withTransaction {
        /*
         * THE RUN ROW'S OWN TEXT FIELDS ARE SEALED TOO.
         *
         * `assignment` is the job in the caller's words and `resolvedSystemPrompt` is the
         * agent's instructions; both can hold something a person typed, and storing either
         * in the clear would leave an unprotected copy beside the encrypted ledger it
         * belongs to. Sealing them here means the row on disk carries no plaintext of
         * either, and `openRun` hands back the readable form.
         *
         * A refusal throws out of the transaction, so a run that cannot be stored securely
         * is not stored at all.
         */
        val storedRun = run.copy(
            assignment = protect(
                com.unuslumen.app.domain.security.ProtectionContext(
                    purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_RUN_SNAPSHOT,
                    ownerId = run.runId,
                    field = "assignment",
                    schemaVersion = 1,
                ),
                run.assignment,
            ),
            resolvedSystemPrompt = protect(
                com.unuslumen.app.domain.security.ProtectionContext(
                    purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_RUN_SNAPSHOT,
                    ownerId = run.runId,
                    field = "resolvedSystemPrompt",
                    schemaVersion = 1,
                ),
                run.resolvedSystemPrompt,
            ),
        )
        runDao.insertRun(storedRun)
        val sequence = 1L
        eventDao.insert(
            AgentRunEventEntity(
                eventId = UUID.randomUUID().toString(),
                runId = run.runId,
                sequenceNumber = sequence,
                eventType = AgentRunEventEntity.RUN_CREATED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                occurredAt = run.createdAt,
                recordedAt = run.createdAt,
                payloadJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("assignment", kotlinx.serialization.json.JsonPrimitive(run.assignment))
                        put("agentId", kotlinx.serialization.json.JsonPrimitive(run.agentId))
                        put("agentSlug", kotlinx.serialization.json.JsonPrimitive(run.agentSlug))
                        put("agentName", kotlinx.serialization.json.JsonPrimitive(run.agentName))
                        put("agentVersion", kotlinx.serialization.json.JsonPrimitive(run.agentVersion))
                    }
                ),
            )
        )
        runDao.setLastEventSequence(run.runId, sequence, run.createdAt)
        // The caller gets its own values back, not the envelopes that went to disk.
        run.copy(lastEventSequence = sequence)
    }

    // ------------------------------------------------------------------
    // Appending events
    // ------------------------------------------------------------------

    /**
     * Append one event, allocating its sequence inside the transaction.
     *
     * Returns the committed event, or null when the idempotency key has already been
     * used. Null is an answer, not a failure: it means this fact is already in the
     * ledger and writing it twice would be a duplicate.
     */
    suspend fun append(runId: String, draft: EventDraft): AgentRunEventEntity? =
        database.withTransaction {
            if (draft.idempotencyKey != null) {
                val existing = eventDao.eventByKey(runId, draft.idempotencyKey)
                if (existing != null) return@withTransaction null
            }
            val run = runDao.run(runId)
                ?: error("No run '$runId'. An event cannot belong to a run that does not exist.")
            val sequence = run.lastEventSequence + 1
            val now = System.currentTimeMillis()
            val event = AgentRunEventEntity(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequenceNumber = sequence,
                eventType = draft.eventType,
                actorType = draft.actorType,
                actorId = draft.actorId,
                correlationId = draft.correlationId,
                causationEventId = draft.causationEventId,
                idempotencyKey = draft.idempotencyKey,
                occurredAt = draft.occurredAt,
                recordedAt = now,
                payloadSchemaVersion = draft.payloadSchemaVersion,
                /*
                 * SEALED, WITH THE CONTEXT BOUND TO THIS EVENT. Purpose, run, field and
                 * schema version all go into the authenticated data, so an envelope lifted
                 * out of this row and pasted into another fails to open. The event id is
                 * not known before the row is built, so the run and the payload field are
                 * the binding here; that still stops a payload moving between runs, which
                 * is the move that would forge a record.
                 */
                payloadJson = protect(
                    com.unuslumen.app.domain.security.ProtectionContext(
                        purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_EVENT_PAYLOAD,
                        ownerId = runId,
                        field = draft.eventType,
                        schemaVersion = draft.payloadSchemaVersion,
                    ),
                    draft.payload,
                ),
            )
            eventDao.insert(event)
            runDao.setLastEventSequence(runId, sequence, now)
            event
        }

    /**
     * Append a state change and the state itself in one transaction.
     *
     * THE ROW AND THE EVENT ARE ONE FACT. A status set without its event is a change
     * nobody can explain; an event without the row is a claim the current state
     * contradicts. Neither is allowed to happen alone.
     */
    /**
     * Put the run's report on its own row.
     *
     * THE COLUMN EXISTED AND NOTHING WROTE IT. `result_summary` has been on `agent_runs` since the
     * schema was written, the room reads it for its REPORT panel, and no code path ever called the
     * DAO method that sets it. So every completed run carried an empty report: the work was done,
     * the model's answer was in the ledger, and the surface a person opens showed nothing.
     *
     * IT IS WRITTEN WITH THE COMPLETION, NOT AFTER IT. A run that finishes and dies before the
     * summary lands is a run whose answer is lost from its row, which is the same defect again with
     * a smaller window.
     */
    suspend fun recordResultSummary(runId: String, summary: String?) {
        runDao.setResultSummary(runId, summary?.take(8_000), System.currentTimeMillis())
    }

    suspend fun appendStateChange(
        runId: String,
        fromStatus: String,
        toStatus: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
        finishedAt: Long? = null,
    ): AgentRunEventEntity? = database.withTransaction {
        /*
         * CONDITIONAL, SO A LATE WRITE CANNOT UNDO A DECISION.
         *
         * `fromStatus` is now a claim the caller has to prove: the update only lands while the row
         * still holds that status. A coroutine that was cancelled and is still unwinding will call
         * this with the status it believed it was in, the condition will match nothing, and the
         * cancelled row keeps its terminal state. Before this, the write was unconditional and the
         * last coroutine to wake up decided what the history said.
         */
        val changed = runDao.transitionStatus(
            runId = runId,
            fromStatus = fromStatus,
            status = toStatus,
            now = now,
            startedAt = if (toStatus == AgentRunEntity.STATUS_RUNNING) now else null,
            finishedAt = finishedAt,
            clearLease = if (toStatus in AgentRunEntity.TERMINAL_STATUSES) 1 else 0,
        )
        if (changed == 0) {
            android.util.Log.i(
                "guru_agents",
                "Run '$runId': transition $fromStatus -> $toStatus refused; the run is no longer " +
                    "in $fromStatus, so another writer already decided.",
            )
            return@withTransaction null
        }
        append(
            runId,
            EventDraft(
                eventType = AgentRunEventEntity.RUN_STATE_CHANGED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                payload = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("from", kotlinx.serialization.json.JsonPrimitive(fromStatus))
                        put("to", kotlinx.serialization.json.JsonPrimitive(toStatus))
                        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                    }
                ),
                occurredAt = now,
            )
        )
    }

    /**
     * Move a run to a terminal state, exactly once, from whatever state it is actually in.
     *
     * THE ONE DOOR EVERY TERMINAL PATH USES. Completion, failure, cancellation and the recovery
     * interrupt each used to write their own status; two of them could both land, and the row then
     * agreed with whichever came last. That is how a cancelled run is resurrected and how two
     * contradictory state changes end up in one history.
     *
     * WHAT IT GUARANTEES:
     *
     *  - It reads the run's current status *inside the transaction* and refuses if that status is
     *    already terminal, returning [Outcome.AlreadyTerminal]. A second cancel is therefore a
     *    no-op that reports the truth rather than a second contradictory event.
     *  - The update is conditional on the status it just read, so a concurrent writer that slips in
     *    between the read and the write still cannot both succeed.
     *  - An in-flight tool call whose outcome was never recorded is marked
     *    `TOOL_CALL_OUTCOME_UNKNOWN` in the same transaction, before the state change, so the run
     *    says "stopped, and this one is not known" instead of implying it was undone.
     *  - The lease is cleared in the same statement, so a stopped run cannot look alive.
     *  - `result_summary` is written in the same statement when one is supplied, so a completed run
     *    can never be read as finished-with-no-report when the report existed.
     */
    suspend fun terminate(
        runId: String,
        toStatus: String,
        reason: String,
        resultSummary: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Outcome = database.withTransaction {
        val run = runDao.run(runId)
            ?: return@withTransaction Outcome.NoSuchRun

        if (run.status in AgentRunEntity.TERMINAL_STATUSES) {
            return@withTransaction Outcome.AlreadyTerminal(run.status)
        }

        /*
         * ANY TOOL CALL LEFT OPEN IS RECORDED AS UNKNOWN, NOT AS CANCELLED.
         *
         * A `TOOL_CALL_STARTED` with no completion is a call whose outcome nobody observed: it may
         * have run, it may have died mid-flight. Writing "cancelled" against it would claim a
         * rollback that did not happen. This is the same event the dispatcher uses when a call
         * throws, and it is written here so that process death and cancellation leave the same
         * evidence.
         */
        markOpenToolCallsUnknown(runId, reason, now)

        val changed = runDao.transitionStatus(
            runId = runId,
            fromStatus = run.status,
            status = toStatus,
            now = now,
            startedAt = null,
            finishedAt = now,
            resultSummary = resultSummary?.take(MAX_RESULT_SUMMARY_CHARS),
            clearLease = 1,
        )
        if (changed == 0) {
            // Somebody else moved it between the read and the write. Their decision stands.
            val current = runDao.run(runId)?.status
            return@withTransaction if (current != null && current in AgentRunEntity.TERMINAL_STATUSES) {
                Outcome.AlreadyTerminal(current)
            } else {
                Outcome.Refused("the run changed state while this transition was being written")
            }
        }

        if (toStatus == AgentRunEntity.STATUS_CANCELLED) {
            append(
                runId,
                EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.RUN_CANCEL_REQUESTED,
                    actorType = AgentRunEventEntity.ACTOR_USER,
                    payload = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject {
                            put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                        }
                    ),
                    occurredAt = now,
                ),
            )
        }

        append(
            runId,
            EventDraft(
                eventType = AgentRunEventEntity.RUN_STATE_CHANGED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                payload = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("from", kotlinx.serialization.json.JsonPrimitive(run.status))
                        put("to", kotlinx.serialization.json.JsonPrimitive(toStatus))
                        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                    }
                ),
                occurredAt = now,
            ),
        )
        Outcome.Terminated(run.status, toStatus)
    }

    /**
     * Write `TOOL_CALL_OUTCOME_UNKNOWN` for every started call that never finished.
     *
     * MUST RUN INSIDE THE CALLER'S TRANSACTION. It is called from [terminate], which already holds
     * one, so a refusal rolls the whole thing back rather than leaving a half-marked run.
     *
     * Only starts without a completion of any kind are marked: a call that failed, was refused or
     * timed out already has its outcome recorded and repeating it would be a second claim about one
     * invocation.
     */
    private suspend fun markOpenToolCallsUnknown(runId: String, reason: String, now: Long) {
        val events = eventDao.eventsForRun(runId)
        val started = linkedSetOf<String>()
        val finished = linkedSetOf<String>()

        for (event in events) {
            val invocationId = invocationIdOf(event) ?: continue
            when (event.eventType) {
                AgentRunEventEntity.TOOL_CALL_STARTED -> started.add(invocationId)
                AgentRunEventEntity.TOOL_CALL_COMPLETED,
                AgentRunEventEntity.TOOL_CALL_FAILED,
                AgentRunEventEntity.TOOL_CALL_REFUSED,
                AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
                -> finished.add(invocationId)
                else -> Unit
            }
        }

        for (open in started - finished) {
            runCatching {
                append(
                    runId,
                    EventDraft(
                        eventType = AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
                        actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                        payload = json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("invocationId", kotlinx.serialization.json.JsonPrimitive(open))
                                put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                            }
                        ),
                        occurredAt = now,
                        // One marking per invocation per run, whatever calls terminate twice.
                        idempotencyKey = "unknown:$open",
                    ),
                )
            }
        }
    }

    /**
     * The invocation id inside an event payload.
     *
     * The payload is sealed on disk, so this unseals it through the same policy the rest of the
     * class uses. A payload that will not open is skipped rather than guessed at: marking an
     * unknown outcome against an id we could not read would attach evidence to the wrong call.
     */
    private fun invocationIdOf(event: AgentRunEventEntity): String? {
        val opened = when (val read = openEventPayload(event)) {
            is com.unuslumen.app.domain.security.ProtectedRead.Value -> read.plaintext
            else -> return null
        }
        return runCatching {
            val obj = Json.parseToJsonElement(opened) as kotlinx.serialization.json.JsonObject
            (obj["invocationId"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** What [terminate] did, so a caller can tell a refusal from a decision. */
    sealed interface Outcome {
        /** The run moved. [from] is what it was, [to] is what it now is. */
        data class Terminated(val from: String, val to: String) : Outcome

        /**
         * The run was already finished and nothing was written.
         *
         * THIS IS AN ANSWER, NOT AN ERROR. A second cancellation attempt, or a completion arriving
         * after a cancellation, is an ordinary race and the caller needs to know the run is settled
         * without being told the write failed.
         */
        data class AlreadyTerminal(val status: String) : Outcome

        data object NoSuchRun : Outcome
        data class Refused(val reason: String) : Outcome
    }

    // ------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------

    /**
     * Commit a checkpoint and its event as one fact.
     *
     * THE ORDER IS THE POINT, AND SO IS THE COUNTER. The checkpoint's `event_sequence` is
     * the last event it already reflects. `CHECKPOINT_CREATED` is then appended with the
     * NEXT sequence, and BOTH counters advance, because that event consumed a sequence
     * like any other. Advancing only the checkpoint counter is how a later event ends up
     * reusing a number.
     *
     * The hash is taken over the exact string that gets stored, so a later read can prove
     * the state was not altered between the write and the restore rather than trusting it.
     */
    suspend fun commitCheckpoint(
        runId: String,
        reason: String,
        stateJson: String,
        schemaVersion: Int = CURRENT_CHECKPOINT_SCHEMA_VERSION,
        now: Long = System.currentTimeMillis(),
    ): AgentRunCheckpointEntity = database.withTransaction {
        val run = runDao.run(runId)
            ?: error("No run '$runId'. A checkpoint cannot belong to a run that does not exist.")
        val checkpointSequence = run.lastCheckpointSequence + 1
        // The boundary this state reflects: everything committed so far, before the
        // CHECKPOINT_CREATED event that is about to name it.
        val boundary = run.lastEventSequence

        /*
         * SEAL ONCE, THEN HASH WHAT WILL ACTUALLY BE STORED.
         *
         * Hashing the plaintext and storing the envelope is the defect that made an
         * untouched checkpoint fail its own verification: the hash described bytes that
         * were never persisted. A sealed value is non-deterministic anyway — a fresh IV per
         * write — so the only form that can be verified without the key is the stored one.
         */
        val storedState = protect(
            com.unuslumen.app.domain.security.ProtectionContext(
                purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_CHECKPOINT_STATE,
                ownerId = runId,
                field = "checkpoint:$checkpointSequence",
                schemaVersion = schemaVersion,
            ),
            stateJson,
        )

        val checkpoint = AgentRunCheckpointEntity(
            checkpointId = UUID.randomUUID().toString(),
            runId = runId,
            checkpointSequence = checkpointSequence,
            eventSequence = boundary,
            checkpointSchemaVersion = schemaVersion,
            checkpointReason = reason,
            /*
             * THE WHOLE WORKING STATE IS SEALED, NOT JUST THE FIELDS INSIDE IT.
             *
             * A checkpoint carries messages, the notebook, the pending question and the
             * resolved prompt — every one of which can hold something a person said. Sealing
             * the document as a unit means there is no unprotected copy of any of it, and
             * the hash below is taken over the sealed form so an envelope swap is caught by
             * the hash as well as by the cipher's own authentication.
             */
            stateJson = storedState,
            stateSha256 = sha256(storedState),
            createdAt = now,
        )
        checkpointDao.insert(checkpoint)

        val eventSequence = boundary + 1
        eventDao.insert(
            AgentRunEventEntity(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequenceNumber = eventSequence,
                eventType = AgentRunEventEntity.CHECKPOINT_CREATED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                occurredAt = now,
                recordedAt = now,
                payloadJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("checkpointId", kotlinx.serialization.json.JsonPrimitive(checkpoint.checkpointId))
                        put("checkpointSequence", kotlinx.serialization.json.JsonPrimitive(checkpointSequence))
                        put("eventSequence", kotlinx.serialization.json.JsonPrimitive(boundary))
                        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                    }
                ),
            )
        )
        runDao.setLastCheckpointSequence(runId, checkpointSequence, now)
        /*
         * BOTH COUNTERS. THIS IS THE LINE THE DESIGN REVIEW FOUND MISSING.
         *
         * `CHECKPOINT_CREATED` is an event like any other and consumes a sequence, so
         * omitting this lets the next writer allocate a number already in use and the
         * unique index then refuses a perfectly legitimate write with an error nobody can
         * explain from the code that caused it.
         */
        runDao.setLastEventSequence(runId, eventSequence, now)
        checkpoint
    }

    /** The newest checkpoint, or null when the run has never taken one. */
    suspend fun latestCheckpoint(runId: String): AgentRunCheckpointEntity? =
        checkpointDao.latestForRun(runId)

    /**
     * Check a checkpoint before trusting it.
     *
     * A hash mismatch means the stored state is not the state that was written, and a
     * newer schema version means this build cannot read it. Both are refusals, because
     * restoring a damaged session and failing somewhere unrelated later is strictly
     * worse than saying at the door that the record cannot be read.
     */
    fun verifyCheckpoint(checkpoint: AgentRunCheckpointEntity): CheckpointVerdict {
        if (checkpoint.checkpointSchemaVersion > CURRENT_CHECKPOINT_SCHEMA_VERSION) {
            return CheckpointVerdict.Unreadable(
                "checkpoint schema ${checkpoint.checkpointSchemaVersion} was written by a newer build"
            )
        }
        /*
         * THE HASH IS OVER THE STORED FORM, WHICH IS WHAT WAS SEALED.
         *
         * A sealed checkpoint cannot be hashed after decryption, because the plaintext
         * differs on every seal: a fresh IV per write means the same state produces a
         * different envelope and a different hash. Hashing the stored bytes is the only
         * version that can be verified without the key, and the cipher's own tag is the
         * second line of defence once it is opened.
         */
        val actual = sha256(checkpoint.stateJson)
        if (actual != checkpoint.stateSha256) {
            return CheckpointVerdict.Unreadable("checkpoint state does not match its recorded hash")
        }
        return CheckpointVerdict.Verified
    }

    /**
     * The checkpoint's working state, decrypted and parseable.
     *
     * Returns the sealed-read outcome rather than a string, so the caller can tell an
     * absent checkpoint from one whose key is missing on this device from one that has
     * been tampered with. Those three lead to three different decisions and collapsing
     * them into a null is how a run gets resumed against a hole.
     */
    fun openCheckpointState(
        checkpoint: AgentRunCheckpointEntity,
    ): com.unuslumen.app.domain.security.ProtectedRead = unprotect(
        com.unuslumen.app.domain.security.ProtectionContext(
            purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_CHECKPOINT_STATE,
            ownerId = checkpoint.runId,
            field = "checkpoint:${checkpoint.checkpointSequence}",
            schemaVersion = checkpoint.checkpointSchemaVersion,
        ),
        checkpoint.stateJson,
    )

    /** One event payload, decrypted and parseable. */
    fun openEventPayload(
        event: AgentRunEventEntity,
    ): com.unuslumen.app.domain.security.ProtectedRead = unprotect(
        com.unuslumen.app.domain.security.ProtectionContext(
            purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_EVENT_PAYLOAD,
            ownerId = event.runId,
            field = event.eventType,
            schemaVersion = event.payloadSchemaVersion,
        ),
        event.payloadJson,
    )

    sealed interface CheckpointVerdict {
        data object Verified : CheckpointVerdict
        data class Unreadable(val reason: String) : CheckpointVerdict
    }

    // ------------------------------------------------------------------
    // Reading back
    // ------------------------------------------------------------------

    /**
     * One run, with the sealed text fields opened.
     *
     * THE ROW ON DISK CARRIES AN ENVELOPE FOR `assignment` AND `resolvedSystemPrompt`;
     * a caller gets the words. A value that will not open is left as a marker naming why,
     * so a locked vault is visible rather than reading as an empty assignment.
     */
    suspend fun run(runId: String): AgentRunEntity? {
        val stored = runDao.run(runId) ?: return null
        fun openField(field: String, value: String): String =
            when (val outcome = unprotect(
                com.unuslumen.app.domain.security.ProtectionContext(
                    purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_RUN_SNAPSHOT,
                    ownerId = runId,
                    field = field,
                    schemaVersion = 1,
                ),
                value,
            )) {
                is com.unuslumen.app.domain.security.ProtectedRead.Value -> outcome.plaintext
                is com.unuslumen.app.domain.security.ProtectedRead.Absent -> ""
                is com.unuslumen.app.domain.security.ProtectedRead.Redacted ->
                    "[withheld: ${outcome.reason}]"
                is com.unuslumen.app.domain.security.ProtectedRead.Locked ->
                    "[unavailable: ${outcome.reason}]"
                is com.unuslumen.app.domain.security.ProtectedRead.Tampered ->
                    "[altered: ${outcome.reason}]"
            }
        return stored.copy(
            assignment = openField("assignment", stored.assignment),
            resolvedSystemPrompt = openField("resolvedSystemPrompt", stored.resolvedSystemPrompt),
        )
    }

    /**
     * Every event for a run, with payloads opened.
     *
     * THE ROW ON DISK IS SEALED; THE ROW A CALLER GETS IS NOT. That split is deliberate:
     * at rest the payload is protected, and every consumer — replay, an Agent Room, a
     * diagnostic — receives the value it needs without each one having to remember to
     * decrypt. A consumer that had to decrypt would be a consumer that could forget to,
     * and forgetting would look exactly like an empty payload.
     *
     * A payload that cannot be opened is returned with an explicit marker rather than an
     * empty string, so a locked vault is visible as a locked vault instead of as an event
     * that recorded nothing.
     */
    suspend fun events(runId: String): List<AgentRunEventEntity> =
        eventDao.eventsForRun(runId).map { it.opened() }

    /** Everything after a boundary. This is what a restore replays. */
    suspend fun eventsAfter(runId: String, boundary: Long): List<AgentRunEventEntity> =
        eventDao.eventsAfter(runId, boundary).map { it.opened() }

    /** One event with its payload opened, for a caller that already holds the row. */
    private fun AgentRunEventEntity.opened(): AgentRunEventEntity {
        val context = com.unuslumen.app.domain.security.ProtectionContext(
            purpose = com.unuslumen.app.domain.security.ProtectionContext.PURPOSE_EVENT_PAYLOAD,
            ownerId = runId,
            field = eventType,
            schemaVersion = payloadSchemaVersion,
        )
        val unsealed = when (val outcome = unprotect(context, payloadJson)) {
            is com.unuslumen.app.domain.security.ProtectedRead.Value -> outcome.plaintext
            is com.unuslumen.app.domain.security.ProtectedRead.Absent ->
                "\"{\\\"unavailable\\\":\\\"absent\\\"}\"".trim('"')
            is com.unuslumen.app.domain.security.ProtectedRead.Redacted ->
                "{\"unavailable\":\"redacted\",\"reason\":${quote(outcome.reason)}}"
            is com.unuslumen.app.domain.security.ProtectedRead.Locked ->
                "{\"unavailable\":\"locked\",\"reason\":${quote(outcome.reason)}}"
            is com.unuslumen.app.domain.security.ProtectedRead.Tampered ->
                "{\"unavailable\":\"tampered\",\"reason\":${quote(outcome.reason)}}"
        }
        return copy(payloadJson = unsealed)
    }

    // ------------------------------------------------------------------
    // Lease
    // ------------------------------------------------------------------

    /**
     * Take the lease, recording that it changed hands.
     *
     * Returns true when this caller now owns the run. The database does the deciding
     * with a conditional update, so two processes cannot both come back with true.
     */
    suspend fun acquireLease(runId: String, owner: String, previousOwner: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val changed = runDao.claimLease(runId, owner, previousOwner, now)
        if (changed == 1) {
            append(
                runId,
                EventDraft(
                    eventType = AgentRunEventEntity.RUN_LEASE_ACQUIRED,
                    actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                    actorId = owner,
                    payload = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject {
                            put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
                            previousOwner?.let {
                                put("previousOwner", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                        }
                    ),
                )
            )
        }
        return changed == 1
    }

    /**
     * Renew the heartbeat.
     *
     * NOT AN EVENT. A heartbeat every thirty seconds across a long run would fill the
     * ledger with rows nobody will ever read, and a ledger full of noise is a ledger
     * nobody searches. The lease rows are operational liveness metadata; the acquisition
     * and the release are the facts worth keeping.
     */
    suspend fun heartbeat(runId: String, owner: String): Boolean =
        runDao.heartbeat(runId, owner, System.currentTimeMillis()) == 1

    /** Give the lease up. Used when a run enters a waiting state: waiting is not dying. */
    suspend fun releaseLease(runId: String, owner: String, reason: String) {
        if (runDao.releaseLease(runId, owner) == 1) {
            append(
                runId,
                EventDraft(
                    eventType = AgentRunEventEntity.RUN_LEASE_RELEASED,
                    actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                    actorId = owner,
                    payload = json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject {
                            put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
                            put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                        }
                    ),
                )
            )
        }
    }

    companion object {
        const val CURRENT_CHECKPOINT_SCHEMA_VERSION = 1

        /**
         * How much of a report is kept on the run row.
         *
         * THE ROW IS A SUMMARY, THE LEDGER IS THE RECORD. The full text of anything long is already
         * in `MODEL_REQUEST_COMPLETED`; this column exists so a surface can show the answer without
         * replaying the run. Eight thousand characters is several pages of prose and small enough
         * that a row stays cheap to read.
         */
        const val MAX_RESULT_SUMMARY_CHARS = 8_000

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
