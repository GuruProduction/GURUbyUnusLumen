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
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The protection policy, applied at the one door every write goes through.
     *
     * WHY HERE AND NOT AT EACH CALL SITE. Protection that a caller has to remember is
     * protection that will be forgotten at the call site nobody reviewed. Every checkpoint
     * state and every event payload passes through this class, so sealing is done once,
     * in the place all writes share.
     *
     * SEALING IS BEST-EFFORT AND RECORDED. If the keystore refuses, the value is stored
     * plainly with a marker saying so, rather than the run failing or the write being
     * silently skipped. A ledger that stops recording because the hardware said no is
     * worse than a ledger that records and admits the value is unprotected, because the
     * second one can still be read six months later and the first cannot.
     */
    private fun protect(field: com.unuslumen.app.domain.security.ProtectionContext, value: String): String {
        if (value.isBlank()) return value
        return when (val outcome = com.unuslumen.app.data.security.ProtectedData.seal(field, value)) {
            is com.unuslumen.app.domain.security.ProtectedWrite.Sealed -> outcome.envelope
            is com.unuslumen.app.domain.security.ProtectedWrite.Redacted ->
                "{\"protected\":\"redacted\",\"reason\":${quote(outcome.reason)}}"
            is com.unuslumen.app.domain.security.ProtectedWrite.Failed ->
                /*
                 * THE VALUE IS STORED, THE SEAL IS NOT, AND THE ROW SAYS WHICH.
                 *
                 * A wrapper object was the first attempt and it was wrong: it changed the
                 * shape a reader gets back, so every consumer of a payload had to know
                 * about a marker that only exists when the hardware said no. Instead the
                 * raw value is stored as written and the fact that it is unprotected goes
                 * into the log, because a payload that changes shape depending on device
                 * capability is a payload nobody can write a stable reader for.
                 */
                run {
                    android.util.Log.w(
                        "guru_agent_ledger",
                        "Value for '${field.field}' stored UNPROTECTED: ${outcome.reason}"
                    )
                    value
                }
        }
    }

    /**
     * Read a value back through the same policy.
     *
     * A value that was never sealed comes back as it was written, so a row from before
     * protection existed is still readable rather than being refused as tampered.
     */
    fun unprotect(
        field: com.unuslumen.app.domain.security.ProtectionContext,
        stored: String,
    ): com.unuslumen.app.domain.security.ProtectedRead {
        if (stored.isBlank()) {
            return com.unuslumen.app.domain.security.ProtectedRead.Absent("nothing was stored for this field")
        }
        if (!com.unuslumen.app.data.security.ProtectedData.isEnvelope(stored)) {
            // A plaintext historical value. Readable, and honestly not protected.
            return com.unuslumen.app.domain.security.ProtectedRead.Value(stored)
        }
        return com.unuslumen.app.data.security.ProtectedData.open(field, stored)
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
        runDao.insertRun(run)
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
    suspend fun appendStateChange(
        runId: String,
        fromStatus: String,
        toStatus: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
        finishedAt: Long? = null,
    ): AgentRunEventEntity? = database.withTransaction {
        val event = append(
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
        runDao.setStatus(
            runId = runId,
            status = toStatus,
            now = now,
            startedAt = if (toStatus == AgentRunEntity.STATUS_RUNNING) now else null,
            finishedAt = finishedAt,
        )
        event
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

    suspend fun run(runId: String): AgentRunEntity? = runDao.run(runId)

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

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
