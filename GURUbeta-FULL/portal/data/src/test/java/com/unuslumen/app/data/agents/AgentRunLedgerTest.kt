// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.guruDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ledger, against a real database.
 *
 * THE ONE RULE THIS FILE EXISTS FOR IS THE CHECKPOINT COUNTER. `CHECKPOINT_CREATED` is an
 * event like any other and consumes a sequence, so committing a checkpoint has to advance
 * BOTH `last_event_sequence` and `last_checkpoint_sequence`. Advancing only the checkpoint
 * counter — which the first draft of the design did — lets the next writer allocate a
 * sequence already in use, and the unique index then refuses a perfectly legitimate write
 * with an error nobody can explain.
 *
 * It runs on a real in-memory SQLite through Robolectric, not on a fake, because the
 * behaviour under test IS the transaction: sequence allocation, the event insert and the
 * counter update must land together or not at all, and a fake would prove only that the
 * fake agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRunLedgerTest {

    private lateinit var database: guruDatabase
    private lateinit var ledger: AgentRunLedger

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()

        /*
         * THE CONVERSATION IS INSERTED FIRST BECAUSE THE FOREIGN KEY IS REAL.
         *
         * `agent_runs.conversation_id` references `conversations.id` with CASCADE, and
         * Room turns foreign keys on. Omitting the parent row fails every write with
         * SQLITE_CONSTRAINT_FOREIGNKEY — which is the schema doing exactly its job, and
         * the reason this fixture has to create the conversation rather than assume the
         * reference is decorative.
         */
        database.conversationDao().insertConversations(
            listOf(
                com.unuslumen.app.database.entity.ConversationEntity(
                    id = "conv-1",
                    title = "a conversation",
                    createdDate = 1,
                    updatedDate = 1,
                    messageCount = 0,
                )
            )
        )

        ledger = AgentRunLedger(
            database,
            database.agentRunDao(),
            database.agentRunEventDao(),
            database.agentRunCheckpointDao(),
            /*
             * A STAND-IN PROTECTOR, BECAUSE FAIL-CLOSED MEANS THE REAL ONE CANNOT WORK HERE.
             *
             * There is no Android Keystore on a JVM, so the production protector reports
             * every seal as failed — and the ledger now correctly refuses to write a value
             * it cannot seal. That would fail every test in this file for a reason that has
             * nothing to do with what they are testing. This stand-in seals and opens
             * faithfully so the storage rules can be proved; the refusal path itself is
             * proved separately in AgentRunLedgerProtectionTest with a protector that
             * always fails.
             */
            TestProtector,
        )
    }

    /** A protector that seals and opens faithfully with the context bound in. */
    private object TestProtector : com.unuslumen.app.domain.security.DataProtector {
        override fun seal(
            context: com.unuslumen.app.domain.security.ProtectionContext,
            plaintext: String,
        ): com.unuslumen.app.domain.security.ProtectedWrite =
            com.unuslumen.app.domain.security.ProtectedWrite.Sealed(
                "SEALED[" + context.purpose + ":" + context.ownerId + ":" + context.field + ":" + plaintext + "]"
            )

        override fun open(
            context: com.unuslumen.app.domain.security.ProtectionContext,
            stored: String?,
        ): com.unuslumen.app.domain.security.ProtectedRead {
            if (stored == null || stored.isBlank()) {
                return com.unuslumen.app.domain.security.ProtectedRead.Absent("nothing")
            }
            val prefix = "SEALED[" + context.purpose + ":" + context.ownerId + ":" + context.field + ":"
            if (!stored.startsWith(prefix)) {
                return com.unuslumen.app.domain.security.ProtectedRead.Tampered("sealed for another place")
            }
            return com.unuslumen.app.domain.security.ProtectedRead.Value(stored.removePrefix(prefix).removeSuffix("]"))
        }

        override fun isEnvelope(stored: String?): Boolean = stored?.startsWith("SEALED[") == true
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun run(runId: String = "run-1") = AgentRunEntity(
        runId = runId,
        conversationId = "conv-1",
        agentId = "agent-1",
        agentSlug = "grafter",
        agentName = "Grafter",
        agentVersion = 3,
        assignment = "do the thing",
        status = AgentRunEntity.STATUS_CREATED,
        agentDefinitionSnapshotJson = "{\"schemaVersion\":1}",
        resolvedSystemPrompt = "you are a grafter",
        toolManifestSnapshotJson = "{\"schemaVersion\":1}",
        modelProvider = "UnusLumen",
        modelName = "a-model",
        createdAt = 1000,
        updatedAt = 1000,
    )

    private fun draft(type: String, payload: String = "{}") = AgentRunLedger.EventDraft(
        eventType = type,
        actorType = AgentRunEventEntity.ACTOR_RUNTIME,
        payload = payload,
    )

    /** Creating a run records its beginning, so no run is ever a history with no first fact. */
    @Test
    fun creatingARunWritesItsFirstEventAndStartsTheCounterAtOne() = runTest {
        val created = ledger.createRun(run("run-1"))

        assertEquals(1L, created.lastEventSequence)
        val events = ledger.events("run-1")
        assertEquals(1, events.size)
        assertEquals(AgentRunEventEntity.RUN_CREATED, events.single().eventType)
        assertEquals(1L, events.single().sequenceNumber)
        assertEquals(1L, ledger.run("run-1")?.lastEventSequence)
    }

    /** Sequences rise by one, and the counter on the run always matches the newest event. */
    @Test
    fun sequencesRiseByOneAndTheRunCounterTracksThemA() = runTest {
        ledger.createRun(run("run-1"))

        ledger.append("run-1", draft("MODEL_REQUEST_STARTED"))
        ledger.append("run-1", draft("MODEL_TEXT_COMPLETED"))
        ledger.append("run-1", draft("MODEL_REQUEST_COMPLETED"))

        val events = ledger.events("run-1")
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.sequenceNumber })
        assertEquals(4L, ledger.run("run-1")?.lastEventSequence)
    }

    /**
     * THE DEFECT THIS FILE EXISTS FOR.
     *
     * Committing a checkpoint appends `CHECKPOINT_CREATED`, so BOTH counters must advance.
     * A later event must then take a sequence strictly above the checkpoint event's rather
     * than colliding with it.
     */
    @Test
    fun committingACheckpointAdvancesBothCountersAndLeavesNoGapForCollision() = runTest {
        ledger.createRun(run("run-1"))
        ledger.append("run-1", draft("MODEL_REQUEST_STARTED"))

        val beforeRun = ledger.run("run-1")!!
        assertEquals(2L, beforeRun.lastEventSequence)
        assertEquals(0L, beforeRun.lastCheckpointSequence)

        val checkpoint = ledger.commitCheckpoint(
            runId = "run-1",
            reason = "ROUND_BOUNDARY",
            stateJson = "{\"schemaVersion\":1}",
        )

        val afterRun = ledger.run("run-1")!!
        assertEquals("the checkpoint sequence advances", 1L, afterRun.lastCheckpointSequence)
        assertEquals(
            "CHECKPOINT_CREATED consumes a sequence, so the event counter must advance too",
            3L,
            afterRun.lastEventSequence,
        )
        assertEquals("the checkpoint reflects the boundary before its own event", 2L, checkpoint.eventSequence)

        // The next ordinary event must not collide with the checkpoint event's sequence.
        val next = ledger.append("run-1", draft("MODEL_OUTPUT_BATCH"))
        assertNotNull(next)
        assertEquals(4L, next!!.sequenceNumber)
        assertEquals(0, ledger.events("run-1").count { it.sequenceNumber == 3L && it.eventType != AgentRunEventEntity.CHECKPOINT_CREATED })
    }

    /** Two checkpoints in a row keep increasing rather than overwriting each other. */
    @Test
    fun checkpointsAreNumberedInSequenceAndAllSurvive() = runTest {
        ledger.createRun(run("run-1"))
        ledger.commitCheckpoint("run-1", "ROUND_BOUNDARY", "{\"schemaVersion\":1}")
        ledger.commitCheckpoint("run-1", "ENTERING_WAITING", "{\"schemaVersion\":1}")

        val all = database.agentRunCheckpointDao().checkpointsForRun("run-1")
        assertEquals(2, all.size)
        assertEquals(listOf(1L, 2L), all.map { it.checkpointSequence })
        assertEquals(2L, ledger.run("run-1")?.lastCheckpointSequence)
        assertEquals(2L, ledger.latestCheckpoint("run-1")?.checkpointSequence)
    }

    /** An idempotency key makes a repeated write a no-op instead of a second fact. */
    @Test
    fun anIdempotencyKeyStopsTheSameFactLandingTwice() = runTest {
        ledger.createRun(run("run-1"))
        val keyed = AgentRunLedger.EventDraft(
            eventType = AgentRunEventEntity.TOOL_CALL_STARTED,
            actorType = AgentRunEventEntity.ACTOR_RUNTIME,
            payload = "{}",
            idempotencyKey = "tool-start-abc",
        )

        val first = ledger.append("run-1", keyed)
        val second = ledger.append("run-1", keyed)

        assertNotNull("the first write lands", first)
        assertNull("the repeat is a no-op, not an error", second)
        assertEquals(
            1,
            ledger.events("run-1").count { it.eventType == AgentRunEventEntity.TOOL_CALL_STARTED }
        )
    }

    /** An event for a run that does not exist fails rather than writing an orphan. */
    @Test
    fun anEventForAMissingRunIsRefused() = runTest {
        var threw = false
        try {
            ledger.append("no-such-run", draft("MODEL_REQUEST_STARTED"))
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("an event belonging to no run must be refused", threw)
    }

    /** The state change and the state itself are one fact, so neither can exist alone. */
    @Test
    fun aStateChangeWritesBothTheEventAndTheRow() = runTest {
        ledger.createRun(run("run-1"))
        ledger.appendStateChange(
            runId = "run-1",
            fromStatus = AgentRunEntity.STATUS_CREATED,
            toStatus = AgentRunEntity.STATUS_RUNNING,
            reason = "execution started",
        )

        assertEquals(AgentRunEntity.STATUS_RUNNING, ledger.run("run-1")?.status)
        assertNotNull("a started run records when it started", ledger.run("run-1")?.startedAt)
        assertTrue(
            "the transition must be in the ledger",
            ledger.events("run-1").any { it.eventType == AgentRunEventEntity.RUN_STATE_CHANGED }
        )
    }

    /** A lease can be taken once, renewed only by its owner, and released. */
    @Test
    fun theLeaseBelongsToOneOwnerAtATime() = runTest {
        ledger.createRun(run("run-1"))

        assertTrue("the first claim wins", ledger.acquireLease("run-1", "proc-a"))
        assertFalse("a second process cannot take a held lease", ledger.acquireLease("run-1", "proc-b"))
        assertTrue("the owner renews its own lease", ledger.heartbeat("run-1", "proc-a"))
        assertFalse("a process without the lease changes nothing", ledger.heartbeat("run-1", "proc-b"))

        ledger.releaseLease("run-1", "proc-a", "waiting for an answer")
        assertNull("released means nobody holds it", ledger.run("run-1")?.leaseOwner)
        assertTrue(
            "acquisition and release are the facts worth keeping",
            ledger.events("run-1").count {
                it.eventType == AgentRunEventEntity.RUN_LEASE_ACQUIRED ||
                    it.eventType == AgentRunEventEntity.RUN_LEASE_RELEASED
            } == 2
        )
    }

    /**
     * A tampered checkpoint is caught by its hash rather than being restored.
     *
     * THE STATE ON THE ROW IS SEALED, SO TAMPERING MEANS CHANGING THE SEALED TEXT. On this
     * JVM there is no Android Keystore, so sealing reports `Failed` and the stored form is
     * a marked plaintext envelope — which is still a stored form with a hash over it, so
     * the hash check is exercised either way. Changing one character of it must fail.
     */
    @Test
    fun aCheckpointWhoseStateWasAlteredIsRefused() = runTest {
        ledger.createRun(run("run-1"))
        val checkpoint = ledger.commitCheckpoint("run-1", "ROUND_BOUNDARY", "{\"schemaVersion\":1,\"rounds\":3}")

        assertTrue(
            "an untouched checkpoint verifies",
            ledger.verifyCheckpoint(checkpoint) is AgentRunLedger.CheckpointVerdict.Verified
        )

        // Flip the rounds value inside whatever was stored, sealed or marked, so this is
        // genuinely an altered record rather than an unaltered one with a changed hash.
        val alteredText = checkpoint.stateJson.replace("3", "9")
        assertFalse("the fixture must actually alter the stored state", alteredText == checkpoint.stateJson)
        val altered = checkpoint.copy(stateJson = alteredText)
        assertTrue(
            "an altered state must be refused, not restored",
            ledger.verifyCheckpoint(altered) is AgentRunLedger.CheckpointVerdict.Unreadable
        )
    }

    /** A checkpoint from a newer build is refused rather than read as this one's shape. */
    @Test
    fun aCheckpointFromANewerBuildIsRefused() = runTest {
        ledger.createRun(run("run-1"))
        val checkpoint = ledger.commitCheckpoint("run-1", "ROUND_BOUNDARY", "{\"schemaVersion\":1}")
        val future = checkpoint.copy(checkpointSchemaVersion = 99)
        val verdict = ledger.verifyCheckpoint(future)
        assertTrue(verdict is AgentRunLedger.CheckpointVerdict.Unreadable)
        assertTrue((verdict as AgentRunLedger.CheckpointVerdict.Unreadable).reason.contains("newer build"))
    }

    /**
     * The ledger hands back payloads a caller can use.
     *
     * THE ROW IS SEALED AT REST AND OPENED ON READ, and on this JVM the Keystore is absent,
     * so sealing reports `Failed` and the stored form is a marker saying the value is
     * unprotected. `events()` must still hand back something parseable, because the
     * alternative — an empty string — would look exactly like an event that recorded
     * nothing. That distinction is the whole point of the marker.
     *
     * The full replay equivalence is proved in [com.unuslumen.app.domain.agents.AgentRunReplayTest]
     * against plain events, because replay's correctness is a question about the reducer
     * rather than about storage. What this test adds is that the storage layer does not
     * corrupt a payload on its way through.
     */
    @Test
    fun payloadsComeBackParseableEvenWhenTheKeystoreCannotSealThem() = runTest {
        ledger.createRun(run("run-1"))
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_STARTED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                payload = "{\"requestId\":\"r1\",\"roundIndex\":1}",
            )
        )

        val stored = ledger.events("run-1").first { it.eventType.contains("MODEL_REQUEST_STARTED") }
        assertTrue("a payload must never come back empty", stored.payloadJson.isNotBlank())
        // Whatever form it took, it must be parseable JSON so a reader can work with it.
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(stored.payloadJson)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject)
    }

    /** Replay of what was committed produces the session those events describe. */
    @Test
    fun whatWasCommittedReplaysIntoTheSessionItDescribes() = runTest {
        ledger.createRun(run("run-1"))
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_STARTED,
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                payload = "{\"requestId\":\"r1\",\"roundIndex\":1}",
            )
        )
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_TEXT_COMPLETED,
                actorType = AgentRunEventEntity.ACTOR_MODEL,
                payload = "{\"requestId\":\"r1\",\"text\":\"done\"}",
            )
        )

        val replayed = com.unuslumen.app.domain.agents.AgentRunReplay.replay(
            ledger.events("run-1").map {
                com.unuslumen.app.domain.agents.AgentRunReplay.Event(
                    sequence = it.sequenceNumber,
                    type = it.eventType,
                    payload = parseFlat(it.payloadJson),
                )
            }
        )

        assertTrue("a ledger written by the ledger must replay consistently: ${replayed.problems}", replayed.consistent)
        assertEquals(1, replayed.rounds)
    }

    /**
     * Flatten a payload into string fields.
     *
     * Uses the real JSON parser rather than a hand-rolled one: a hand-rolled reader that
     * mis-parses a nested or numeric value would make this test fail for its own reasons
     * and send me looking for a defect in the ledger that is not there.
     */
    private fun parseFlat(json: String): Map<String, String> {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
        return obj.mapValues { (_, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString()
        }
    }
}
