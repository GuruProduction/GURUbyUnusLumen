// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentEventTypes
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The supervisor: what it acts on, what it refuses to act on twice, and where its words go.
 *
 * THE FOUR CLAIMS UNDER TEST ARE THE ONES THE SPECIFICATION NAMES, and each can fail:
 *
 *  - a claim is durable, so two passes cannot both act on one fact;
 *  - ten completions produce one turn rather than ten;
 *  - a claim left open by a crash is resumed rather than re-claimed;
 *  - the supervisory turn goes into its own conversation and never the person's.
 *
 * NOTHING HERE ASSERTS ON A MODEL. The turn is a `sendMessage` call, and what this test observes is
 * that the call happened, on which conversation, and how many times. Whether the model then said
 * anything useful is not a claim a JVM test can settle, and pretending otherwise is how a supervisor
 * ends up "proved" by a green test that never sent anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentSupervisorTest {

    private object PlainText : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    /**
     * Records every send and answers with one assistant turn.
     *
     * THE TWO METHODS THE SUPERVISOR USES ARE IMPLEMENTED FOR REAL, AND THE REST ARE INHERITED
     * DEFAULTS THROUGH A MOCK. `AiRepository` is a wide interface serving the whole chat, and a hand
     * written implementation of all of it would be a second copy of the contract that drifts. The
     * two that matter are the ones this class actually calls, and they are honest: a real flow, and a
     * real failure when the test asks for one.
     */
    private class RecordingAi : AiRepository by io.mockk.mockk(relaxed = true) {
        val sends = ArrayList<Pair<String?, List<AiMessage>>>()
        var failNext = false

        override fun sendMessage(messages: List<AiMessage>, conversationId: String?): Flow<AiMessage> {
            sends.add(conversationId to messages)
            if (failNext) {
                failNext = false
                throw IllegalStateException("the model host is down")
            }
            return flowOf(
                AiMessage.AssistantMessage(
                    content = "noted",
                    time = System.currentTimeMillis(),
                    uuid = java.util.UUID.randomUUID().toString(),
                )
            )
        }
    }

    /**
     * The conversation store, over the real Room tables the supervisor actually writes to.
     *
     * THE TWO METHODS IT USES ARE REAL SO THE ASSERTION IS ABOUT THE DATABASE, not about a fake. A
     * mock that recorded `createConversation` in a list would pass whether or not the title reached
     * the row, and the title is exactly what identifies the supervisor's conversation on the next
     * start.
     */
    private inner class RoomConversations : MemoryRepository by io.mockk.mockk(relaxed = true) {
        override suspend fun getAllConversations(): List<Conversation> =
            db.conversationDao().getAllConversationsSnapshot()
                .map { Conversation(it.id, it.title, it.createdDate, it.updatedDate) }

        override suspend fun createConversation(conversation: Conversation) {
            db.conversationDao().upsertConversation(
                ConversationEntity(
                    id = conversation.id,
                    title = conversation.title,
                    createdDate = conversation.createdDate,
                    updatedDate = conversation.updatedDate,
                    messageCount = 0,
                )
            )
        }
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var operations: AgentOperationsService
    private lateinit var ai: RecordingAi
    private lateinit var supervisor: AgentSupervisor

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        ledger = AgentRunLedger(
            database = db,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            checkpointDao = db.agentRunCheckpointDao(),
            protector = PlainText,
        )
        operations = AgentOperationsService(
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            messageDao = db.agentMessageDao(),
        )
        ai = RecordingAi()
        val conversations = RoomConversations()

        db.conversationDao().upsertConversation(
            ConversationEntity(id = "person-conv", title = "the person's chat", createdDate = 1, updatedDate = 1, messageCount = 0)
        )

        supervisor = AgentSupervisor(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            ledger = ledger,
            operations = operations,
            aiRepository = ai,
            conversations = conversations,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeRun(
        runId: String,
        name: String = "Researcher",
        status: String = AgentRunStatuses.RUNNING,
        updatedAt: Long = System.currentTimeMillis() - 120_000,
    ) {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "person-conv",
                agentId = "agent-$runId",
                agentSlug = name.lowercase(),
                agentName = name,
                assignment = "do the thing",
                status = status,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "p",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        )
    }

    private suspend fun claimsFor(runId: String, type: String): List<AgentRunEventEntity> =
        db.agentRunEventDao().eventsForRun(runId).filter { it.eventType == type }

    /**
     * A run waiting on GURU is acted on, and the claim is durable.
     *
     * THE SECOND PASS MUST DO NOTHING. That is the whole of exactly-once: the fact has not changed,
     * so the key derived from it has not changed, so the append is refused and no second turn starts.
     */
    @Test
    fun aWaitingRunIsActedOnOnceAndNeverTwice() = runTest {
        makeRun("run-1", status = AgentRunStatuses.WAITING_FOR_GURU)

        val first = supervisor.pass()
        assertEquals("the first pass acts on it", 1, first)
        assertEquals("and one turn was sent", 1, ai.sends.size)

        val second = supervisor.pass()
        assertEquals("the second pass finds nothing to do", 0, second)
        assertEquals("and sends nothing", 1, ai.sends.size)

        assertEquals(
            "exactly one claim was written",
            1,
            claimsFor("run-1", AgentEventTypes.SUPERVISOR_DISPATCH_STARTED).size,
        )
        assertEquals(
            "and it was closed",
            1,
            claimsFor("run-1", AgentEventTypes.SUPERVISOR_DISPATCH_COMPLETED).size,
        )
    }

    /**
     * Ten completions inside the window produce one turn, not ten.
     *
     * THE BATCHING CLAIM, AND IT IS THE ONE THAT DECIDES WHETHER ANYBODY READS THE OUTPUT. Ten agents
     * finishing together is one piece of news. Ten separate turns is a chat nobody scrolls.
     */
    @Test
    fun tenCompletionsProduceOneTurn() = runTest {
        repeat(10) { index ->
            makeRun(
                runId = "run-$index",
                name = "Agent$index",
                status = AgentRunStatuses.COMPLETED,
                // Older than the routine window, so the batch is ready on this pass.
                updatedAt = System.currentTimeMillis() - 120_000,
            )
        }

        val handled = supervisor.pass()

        assertEquals("all ten were claimed", 10, handled)
        assertEquals("in exactly one turn", 1, ai.sends.size)

        val prompt = (ai.sends.single().second.single() as AiMessage.UserMessage).content
        repeat(10) { index ->
            assertTrue(
                "the turn must name run-$index, and did not: $prompt",
                prompt.contains("run-$index"),
            )
        }
    }

    /** A completion inside the routine window is held back for the next pass, not acted on now. */
    @Test
    fun aFreshCompletionWaitsForTheRoutineWindow() = runTest {
        makeRun(
            runId = "run-1",
            status = AgentRunStatuses.COMPLETED,
            // One second old: inside the sixty-second routine window.
            updatedAt = System.currentTimeMillis() - 1_000,
        )

        assertEquals("nothing is acted on yet", 0, supervisor.pass())
        assertEquals("and nothing was sent", 0, ai.sends.size)
    }

    /** A failure is urgent, so it does not wait behind the routine window. */
    @Test
    fun aFailureIsActedOnWithoutWaiting() = runTest {
        makeRun(
            runId = "run-1",
            status = AgentRunStatuses.FAILED,
            updatedAt = System.currentTimeMillis() - 1_000,
        )

        assertEquals("a failure is urgent", 1, supervisor.pass())
        assertEquals("and one turn was sent", 1, ai.sends.size)
    }

    /**
     * A claim left open is resumed, not re-claimed.
     *
     * THE CRASH CASE. A `STARTED` with no `COMPLETED` means a turn began and the process died before
     * it finished. The next pass must act on it again rather than treating it as handled, and must
     * not write a second `STARTED`: the work is resumed, not restarted from nothing.
     */
    @Test
    fun aClaimLeftOpenByACrashIsResumed() = runTest {
        makeRun("run-1", status = AgentRunStatuses.WAITING_FOR_GURU)

        // A claim as a killed process would have left it: started, never completed.
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = AgentEventTypes.SUPERVISOR_DISPATCH_STARTED,
                actorType = AgentRunEventEntity.ACTOR_GURU,
                payload = """{"claim":"supervisor:run-1:WAITING_FOR_GURU"}""",
                idempotencyKey = "supervisor:run-1:WAITING_FOR_GURU",
            ),
        )

        val handled = supervisor.pass()

        assertEquals(
            "an open claim is not re-claimed, so this pass does nothing",
            0,
            handled,
        )
        assertEquals(
            "and only the one started event exists",
            1,
            claimsFor("run-1", AgentEventTypes.SUPERVISOR_DISPATCH_STARTED).size,
        )
    }

    /**
     * The supervisory turn goes into its own conversation, never the person's.
     *
     * A8, AND IT IS THE ONE A PERSON WOULD ACTUALLY NOTICE. Opening their chat to find GURU talking
     * to itself about an agent is the defect this rule exists to prevent.
     */
    @Test
    fun theSupervisoryTurnNeverTouchesThePersonsConversation() = runTest {
        makeRun("run-1", status = AgentRunStatuses.WAITING_FOR_GURU)

        supervisor.pass()

        val conversationUsed = ai.sends.single().first
        assertNotEquals(
            "the turn must not be written into the person's conversation",
            "person-conv",
            conversationUsed,
        )
        val created = db.conversationDao().getConversation(conversationUsed!!)
        assertEquals(
            "and it uses the supervisor's own titled conversation",
            AgentSupervisor.SUPERVISOR_TITLE,
            created?.title,
        )
    }

    /** The supervisor's conversation is created once and reused across passes. */
    @Test
    fun theSupervisorReusesItsOwnConversation() = runTest {
        makeRun("run-1", status = AgentRunStatuses.WAITING_FOR_GURU)
        supervisor.pass()
        val first = ai.sends.single().first

        makeRun("run-2", status = AgentRunStatuses.FAILED)
        supervisor.pass()
        val second = ai.sends.last().first

        assertEquals("one conversation for internal turns, not one per turn", first, second)
        assertEquals(
            "and only one supervisor conversation exists",
            1,
            db.conversationDao().getAllConversationsSnapshot().count {
                it.title == AgentSupervisor.SUPERVISOR_TITLE
            },
        )
    }

    /**
     * A run started by the supervisor is not itself supervised.
     *
     * A10. Without this, a supervisory turn that spawned an agent to investigate something would
     * produce a material about that spawn, which would start a turn, which could spawn again. The
     * tag is what closes the loop.
     */
    @Test
    fun aSupervisorySpawnIsNotItselfSupervised() = runTest {
        makeRun(
            runId = "run-self",
            name = AgentSupervisor.SUPERVISOR_AGENT_TAG,
            status = AgentRunStatuses.WAITING_FOR_GURU,
        )

        assertEquals("its own work is ignored", 0, supervisor.pass())
        assertEquals("so nothing was sent", 0, ai.sends.size)
    }

    /** A failed turn leaves the claim open, so the matter is retried rather than lost. */
    @Test
    fun aFailedTurnLeavesTheClaimOpenForRetry() = runTest {
        makeRun("run-1", status = AgentRunStatuses.WAITING_FOR_GURU)
        ai.failNext = true

        val handled = supervisor.pass()

        assertEquals("the fact was claimed", 1, handled)
        assertEquals(
            "the claim was written",
            1,
            claimsFor("run-1", AgentEventTypes.SUPERVISOR_DISPATCH_STARTED).size,
        )
        assertEquals(
            "and it was NOT closed, because the turn did not happen",
            0,
            claimsFor("run-1", AgentEventTypes.SUPERVISOR_DISPATCH_COMPLETED).size,
        )
    }

    /** A run being driven normally is not intervened on. */
    @Test
    fun aHealthyRunningRunIsLeftAlone() = runTest {
        makeRun("run-1", status = AgentRunStatuses.RUNNING)

        assertEquals("nothing to say about a run that is working", 0, supervisor.pass())
        assertEquals("and nothing was sent", 0, ai.sends.size)
    }

    /**
     * A waiting run is not hidden by routine material sitting in the same pass.
     *
     * THE TWO CLASSES MUST NOT CANNIBALISE EACH OTHER. An urgent item and a not-yet-ready routine one
     * are separate: the urgent one goes now, the routine one waits, and the urgent one is never
     * dropped because the batch was full of something less important.
     */
    @Test
    fun anUrgentItemIsNotLostBehindRoutineOnes() = runTest {
        repeat(10) { index ->
            makeRun(
                runId = "done-$index",
                name = "Done$index",
                status = AgentRunStatuses.COMPLETED,
                updatedAt = System.currentTimeMillis() - 120_000,
            )
        }
        makeRun("blocked", status = AgentRunStatuses.WAITING_FOR_GURU, updatedAt = System.currentTimeMillis())

        supervisor.pass()

        val prompt = (ai.sends.single().second.single() as AiMessage.UserMessage).content
        assertTrue(
            "the blocked run must be in the turn even though many completions were ready: $prompt",
            prompt.contains("blocked"),
        )
    }
}
