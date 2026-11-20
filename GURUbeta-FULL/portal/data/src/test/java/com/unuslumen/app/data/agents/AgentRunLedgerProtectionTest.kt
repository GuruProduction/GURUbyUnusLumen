// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The protection rule, proved against a stand-in protector.
 *
 * THE TEST THAT MATTERS IS [aProtectorThatCannotSealRefusesTheWriteRatherThanStoringPlaintext].
 * The first version of the ledger failed open: a keystore refusal was logged and the raw
 * value was stored, on the argument that keeping the record mattered more than protecting
 * it. That is wrong for a value classified as protected, because the whole classification
 * exists to keep it out of the clear, and a log line announcing the leak is not a
 * mitigation.
 *
 * This cannot be proved with the real protector on a JVM, because there is no Android
 * Keystore here at all and every seal would fail for the wrong reason. That is why the
 * ledger takes a [DataProtector]: the test can hand it one that always refuses, and the
 * production path keeps the hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRunLedgerProtectionTest {

    private lateinit var database: guruDatabase

    private class RefusingProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Failed("the vault is not available in this test")

        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            ProtectedRead.Locked("not available")

        override fun isEnvelope(stored: String?): Boolean = false
    }

    private class WorkingProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed("SEALED[" + context.purpose + ":" + context.ownerId + ":" + plaintext + "]")

        override fun open(context: ProtectionContext, stored: String?): ProtectedRead {
            if (stored == null) return ProtectedRead.Absent("nothing")
            val expected = "SEALED[" + context.purpose + ":" + context.ownerId + ":"
            if (!stored.startsWith(expected)) return ProtectedRead.Tampered("wrong place")
            return ProtectedRead.Value(stored.removePrefix(expected).removeSuffix("]"))
        }

        override fun isEnvelope(stored: String?): Boolean = stored?.startsWith("SEALED[") == true
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.conversationDao().insertConversations(
            listOf(
                com.unuslumen.app.database.entity.ConversationEntity(
                    id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0,
                )
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun ledgerWith(protector: DataProtector) = AgentRunLedger(
        database,
        database.agentRunDao(),
        database.agentRunEventDao(),
        database.agentRunCheckpointDao(),
        protector,
    )

    private fun run(runId: String) = AgentRunEntity(
        runId = runId,
        conversationId = "conv-1",
        agentId = "agent-1",
        agentSlug = "grafter",
        agentName = "Grafter",
        agentVersion = 1,
        assignment = "do the thing",
        status = AgentRunEntity.STATUS_CREATED,
        agentDefinitionSnapshotJson = "{\"schemaVersion\":1}",
        resolvedSystemPrompt = "you are a grafter",
        toolManifestSnapshotJson = "{\"schemaVersion\":1}",
        modelProvider = "UnusLumen",
        modelName = "a-model",
        createdAt = 1,
        updatedAt = 1,
    )

    /**
     * The defect this file exists for. A protector that cannot seal must stop the write,
     * not store the value in the clear.
     */
    @Test
    fun aProtectorThatCannotSealRefusesTheWriteRatherThanStoringPlaintext() = runTest {
        val ledger = ledgerWith(RefusingProtector())

        /*
         * THE REFUSAL NOW HAPPENS AT CREATE, WHICH IS EARLIER AND BETTER.
         *
         * The run row's own `assignment` and `resolvedSystemPrompt` are sealed too, so a
         * protector that cannot seal stops the run before it exists rather than at the
         * first event. That is the stronger guarantee: there is no window in which a run
         * exists with unsealed text in it. The assertion below therefore covers creation,
         * and the following test covers an event on an already-created run.
         */
        try {
            ledger.createRun(run("run-1"))
            fail("a run whose fields cannot be sealed must not be created at all")
        } catch (e: IllegalStateException) {
            assertTrue(
                "the refusal must name the reason: ${e.message}",
                e.message?.contains("Refusing to write") == true,
            )
        }

        assertTrue(
            "no run row may exist",
            database.agentRunDao().run("run-1") == null,
        )
    }

    /** The event path is separately guarded, on a run created with a protector that worked. */
    @Test
    fun anEventThatCannotBeSealedIsRefusedEvenOnAHealthyRun() = runTest {
        val working = ledgerWith(WorkingProtector())
        working.createRun(run("run-1"))

        // Now hand the same database a protector that refuses, and try to append.
        val refusing = ledgerWith(RefusingProtector())
        try {
            refusing.append(
                "run-1",
                AgentRunLedger.EventDraft(
                    eventType = AgentEventTypeNames.MODEL_REQUEST_STARTED,
                    actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                    payload = "{\"secret\":\"a person's private words\"}",
                )
            )
            fail("an unsealable payload must not be written at all")
        } catch (e: IllegalStateException) {
            assertTrue(
                "the refusal must name the reason, not just throw: ${e.message}",
                e.message?.contains("Refusing to write") == true,
            )
        }

        // And nothing leaked into the row, which is the whole point.
        val stored = database.agentRunEventDao().eventsForRun("run-1")
        assertTrue(
            "no event carrying the plaintext may exist",
            stored.none { it.payloadJson.contains("a person's private words") && it.payloadJson.isNotEmpty() }
        )
    }

    /** A checkpoint that cannot be sealed is refused for the same reason. */
    @Test
    fun aCheckpointThatCannotBeSealedIsRefused() = runTest {
        val working = ledgerWith(WorkingProtector())
        working.createRun(run("run-1"))
        val ledger = ledgerWith(RefusingProtector())

        try {
            ledger.commitCheckpoint("run-1", "ROUND_BOUNDARY", "{\"messages\":\"private\"}")
            fail("a checkpoint holding private state must not be written in the clear")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Refusing to write") == true)
        }
        assertFalse(
            "no checkpoint may exist",
            database.agentRunCheckpointDao().checkpointsForRun("run-1").isNotEmpty()
        )
    }

    /** When the protector works, values are sealed with the run bound in and open correctly. */
    @Test
    fun aWorkingProtectorSealsWithTheRunBoundAndOpensOnlyInItsPlace() = runTest {
        val ledger = ledgerWith(WorkingProtector())
        ledger.createRun(run("run-1"))
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypeNames.MODEL_TEXT_COMPLETED,
                actorType = AgentRunEventEntity.ACTOR_MODEL,
                payload = "{\"text\":\"hello\"}",
            )
        )

        val raw = database.agentRunEventDao().eventsForRun("run-1")
            .first { it.eventType == AgentEventTypeNames.MODEL_TEXT_COMPLETED }
        assertTrue(
            "the stored form must be sealed, not plaintext: ${raw.payloadJson}",
            raw.payloadJson.startsWith("SEALED["),
        )

        val opened = ledger.events("run-1")
            .first { it.eventType == AgentEventTypeNames.MODEL_TEXT_COMPLETED }
        assertEquals("{\"text\":\"hello\"}", opened.payloadJson)
    }

    /** A sealed value moved to another run fails to open, which is the point of binding it. */
    @Test
    fun aSealedValueDoesNotOpenUnderAnotherRun() = runTest {
        val ledger = ledgerWith(WorkingProtector())
        ledger.createRun(run("run-1"))
        ledger.createRun(run("run-2"))
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypeNames.MODEL_TEXT_COMPLETED,
                actorType = AgentRunEventEntity.ACTOR_MODEL,
                payload = "{\"text\":\"run one's words\"}",
            )
        )

        val sealed = database.agentRunEventDao().eventsForRun("run-1")
            .first { it.eventType == AgentEventTypeNames.MODEL_TEXT_COMPLETED }

        // The same envelope, opened as though it belonged to run two.
        val moved = ledger.openEventPayload(
            sealed.copy(runId = "run-2")
        )
        assertTrue(
            "an envelope must not open under another run: $moved",
            moved is ProtectedRead.Tampered,
        )
    }

    /** Old plaintext rows stay readable, rather than being refused as tampered. */
    @Test
    fun aPlaintextHistoricalRowIsStillReadable() = runTest {
        val ledger = ledgerWith(WorkingProtector())
        ledger.createRun(run("run-1"))
        // Written directly, as a row from before protection existed would have been.
        database.agentRunEventDao().insert(
            AgentRunEventEntity(
                eventId = "old-1",
                runId = "run-1",
                sequenceNumber = 99,
                eventType = "LEGACY_EVENT",
                actorType = AgentRunEventEntity.ACTOR_RUNTIME,
                occurredAt = 1,
                recordedAt = 1,
                payloadJson = "{\"legacy\":true}",
            )
        )

        val opened = ledger.events("run-1").first { it.eventType == "LEGACY_EVENT" }
        assertEquals("{\"legacy\":true}", opened.payloadJson)
    }
}

/** The names this test writes, kept here so a domain rename is a compile error rather than a silent skip. */
private object AgentEventTypeNames {
    const val MODEL_REQUEST_STARTED = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REQUEST_STARTED
    const val MODEL_TEXT_COMPLETED = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_TEXT_COMPLETED
}
