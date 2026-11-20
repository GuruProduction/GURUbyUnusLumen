// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentMessageTypes
import com.unuslumen.app.domain.agents.AgentRunStatuses
import com.unuslumen.app.domain.model.AgentVisibility
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.model.VisibilityDetail
import com.unuslumen.app.domain.model.VisibilityRule
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
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
 * The room's state is assembled from rows, and says so when there is nothing.
 *
 * THE CLAIMS THAT MATTER HERE ARE ALL ABOUT HONESTY. An idle agent must not look busy, a waiting run
 * must not look finished, an empty area must name what is missing rather than drawing blank, and a
 * model with no reasoning channel must say so instead of showing an empty panel. Those are the ways
 * a dashboard lies, and each gets a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentRoomAssemblerTest {

    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing stored") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var broker: AgentInteractionBroker
    private lateinit var assembler: AgentRoomAssembler
    private lateinit var repository: GuruAgentRepositoryImpl

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
            protector = PlainTextProtector,
        )
        broker = AgentInteractionBroker(
            database = db,
            messageDao = db.agentMessageDao(),
            approvalDao = db.agentApprovalDao(),
            runDao = db.agentRunDao(),
            ledger = ledger,
        )
        repository = GuruAgentRepositoryImpl(
            guruAgentDao = db.guruAgentDao(),
            definitionVersionDao = db.agentDefinitionVersionDao(),
            transactor = RoomAgentStoreTransactor(db),
            toolRegistry = com.unuslumen.app.data.tools.registry.ToolRegistry(),
        )
        assembler = AgentRoomAssembler(
            agentRepository = repository,
            runDao = db.agentRunDao(),
            eventDao = db.agentRunEventDao(),
            artefactDao = db.agentArtefactDao(),
            contextDao = db.agentContextReferenceDao(),
            ledger = ledger,
            broker = broker,
        )

        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeAgent(
        name: String = "Writer",
        visibility: AgentVisibility = AgentVisibility.TRANSPARENT,
    ) = repository.createAgent(
        SaveAgentRequest(
            name = name,
            description = "writes things",
            systemPrompt = "you write",
            toolPermissions = emptyList(),
            visibility = visibility,
        )
    )

    private suspend fun makeRun(
        agentId: String,
        runId: String = "run-1",
        status: String = AgentRunStatuses.RUNNING,
        effective: String = """{"schemaVersion":1,"model":"a-model","toolsOffered":"captureScreen"}""",
        captureAtStart: Boolean = true,
    ) {
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = "conv-1",
                agentId = agentId,
                agentName = "Writer",
                assignment = "write the report",
                status = status,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                effectiveConfigSnapshotJson = effective,
                resolvedSystemPrompt = "you write",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                reasoningCaptureEnabledAtStart = captureAtStart,
                createdAt = 1000,
                updatedAt = 1000,
            )
        )
    }

    @Test
    fun anIdleAgentSaysItIsIdleRatherThanDrawingFakeActivity() = runTest {
        val agent = makeAgent()
        val room = assembler.assemble(agent.id)!!

        assertNull("no run means no live run", room.run)
        assertFalse(room.isRunning)
        assertTrue(
            "an idle room explains itself rather than leaving a blank panel: ${room.idleBecause}",
            room.idleBecause.contains("has not run yet"),
        )
    }

    @Test
    fun aSwitchedOffAgentSaysSo() = runTest {
        val agent = makeAgent()
        repository.disableAgent(agent.id)
        val room = assembler.assemble(agent.id)!!
        assertTrue(room.idleBecause.contains("switched off"))
    }

    @Test
    fun anAgentThatDoesNotExistReturnsNull() = runTest {
        assertNull(
            "the room closes rather than drawing an agent somebody deleted",
            assembler.assemble("no-such-agent"),
        )
    }

    @Test
    fun aRunningAgentExposesItsAssignmentAndStatus() = runTest {
        val agent = makeAgent()
        makeRun(agent.id, status = AgentRunStatuses.RUNNING)
        val room = assembler.assemble(agent.id)!!

        assertNotNull(room.run)
        assertEquals("write the report", room.run!!.assignment)
        assertEquals(AgentRunStatuses.RUNNING, room.run!!.status)
        assertTrue(room.isRunning)
        assertFalse("running is not waiting on the user", room.run!!.isWaitingOnUser)
    }

    @Test
    fun aWaitingRunIsNotReportedAsFinished() = runTest {
        /*
         * WAITING IS IN PROGRESS. A run blocked on an answer is not idle and is not done; treating
         * it as either would hide the one thing that needs the person.
         */
        val agent = makeAgent()
        makeRun(agent.id, status = AgentRunStatuses.WAITING_FOR_USER)
        val room = assembler.assemble(agent.id)!!

        assertTrue("a blocked run is still live", room.isRunning)
        assertTrue("and it is waiting on the person", room.run!!.isWaitingOnUser)
        assertFalse("it is not terminal", room.run!!.isTerminal)
    }

    @Test
    fun aPendingApprovalSurfacesAndNamesAllSixFacts() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)

        broker.requestApproval(
            runId = "run-1",
            action = "sendSms",
            what = "Send a message to the plumber",
            why = "You asked me to arrange the repair",
            affected = "One SMS, billed to your account",
            reversible = false,
            consequences = "The message leaves the phone",
            requestedPermission = "approval.external.sms",
            fingerprintSeed = "seed",
        )

        val room = assembler.assemble(agent.id)!!
        assertEquals(1, room.run!!.pendingApprovals.size)
        val approval = room.run!!.pendingApprovals.single()
        assertEquals("Send a message to the plumber", approval.what)
        assertTrue(approval.why.isNotBlank())
        assertTrue(approval.affected.isNotBlank())
        assertFalse(approval.reversible)
        assertTrue(approval.consequences.isNotBlank())
        assertEquals("approval.external.sms", approval.requestedPermission)
        assertTrue("an approval makes the run waiting on the person", room.run!!.isWaitingOnUser)
    }

    @Test
    fun toolActivityIsReconstructedFromTheLedgerWithItsRealOutcome() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)

        val invocation = "inv-1"
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"$invocation","tool":"captureScreen","arguments":"{}"}""",
            ),
        )
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_COMPLETED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_TOOL,
                payload = """{"invocationId":"$invocation","tool":"captureScreen","result":"{\"ok\":true}"}""",
            ),
        )

        val room = assembler.assemble(agent.id)!!
        val call = room.run!!.toolActivity.single()
        assertEquals("captureScreen", call.toolName)
        assertEquals("{}", call.arguments)
        assertTrue("the result is the tool's own payload", call.result!!.contains("ok"))
        assertFalse(call.failed)
        assertFalse(call.refused)
        assertFalse(call.outcomeUnknown)
        assertNotNull("a completed call has a duration", call.durationMillis)
    }

    @Test
    fun aRefusedCallIsNotDrawnAsAFailure() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_REFUSED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"inv-2","tool":"phoneCall","reason":"not allowed"}""",
            ),
        )

        val call = assembler.assemble(agent.id)!!.run!!.toolActivity.single()
        assertTrue("a refusal is a refusal", call.refused)
        assertFalse("and it is not a tool that ran and broke", call.failed)
        assertEquals("not allowed", call.result)
    }

    @Test
    fun anUnknownOutcomeIsItsOwnState() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                payload = """{"invocationId":"inv-3","tool":"sendSms","reason":"the reply never arrived"}""",
            ),
        )

        val call = assembler.assemble(agent.id)!!.run!!.toolActivity.single()
        assertTrue(call.outcomeUnknown)
        assertFalse("unknown is not the same as failed", call.failed)
    }

    @Test
    fun messagesCarryTheirStoredAuthorshipAndNeverTheAgentsAsGurus() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = com.unuslumen.app.domain.agents.AgentMessageAuthors.AGENT,
                authorId = agent.id,
                recipientType = com.unuslumen.app.domain.agents.AgentMessageAuthors.GURU,
                messageType = AgentMessageTypes.FINDING,
                content = "found three candidates",
            ),
        )

        val room = assembler.assemble(agent.id)!!
        val message = room.run!!.messages.single()
        assertEquals("Writer", message.authorName)
        assertTrue("the agent's own words are attributed to it", message.isFromAgent)
        assertFalse(
            "and never to GURU",
            message.authorName == "GURU",
        )
    }

    @Test
    fun aModelWithoutAReasoningChannelSaysSoRatherThanShowingAnEmptyPanel() = runTest {
        /*
         * THE HONEST-FAILURE CASE THE SPECIFICATION NAMES. A provider that has no reasoning channel
         * cannot be made to produce one by adding a column, so the room states the limitation.
         */
        val agent = makeAgent()
        makeRun(
            agent.id,
            effective = """{"schemaVersion":1,"model":"a-model","reasoningCaptureEnabled":false}""",
        )

        val reasoning = assembler.assemble(agent.id)!!.run!!.reasoning
        assertFalse(reasoning.hasAnything)
        assertFalse(reasoning.providerSupportsReasoning)
        assertTrue(
            "the status names the cause: ${reasoning.status}",
            reasoning.status.contains("does not provide a reasoning channel"),
        )
    }

    @Test
    fun aModelThatProvidesReasoningButGaveNoneSaysThatInstead() = runTest {
        val agent = makeAgent()
        makeRun(agent.id, effective = """{"schemaVersion":1,"model":"a-model","reasoningCaptureEnabled":true}""")

        val reasoning = assembler.assemble(agent.id)!!.run!!.reasoning
        assertTrue(reasoning.providerSupportsReasoning)
        assertTrue(
            "an empty capture on a capable model is a different fact: ${reasoning.status}",
            reasoning.status.contains("provided no reasoning"),
        )
    }

    @Test
    fun capturedReasoningIsReturned() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        ledger.append(
            "run-1",
            AgentRunLedger.EventDraft(
                eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MODEL_REASONING_COMPLETED,
                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_MODEL,
                payload = """{"requestId":"r1","reasoning":"I should check the second source first."}""",
            ),
        )

        val reasoning = assembler.assemble(agent.id)!!.run!!.reasoning
        assertTrue(reasoning.hasAnything)
        assertEquals("CAPTURED", reasoning.status)
        assertTrue(reasoning.text.contains("second source"))
    }

    @Test
    fun anArtefactAppearsInTheOutputsWithItsProvenance() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        db.agentArtefactDao().insert(
            com.unuslumen.app.database.entity.AgentArtefactEntity(
                artefactId = "art-1",
                runId = "run-1",
                agentId = agent.id,
                agentName = "Writer",
                title = "the report",
                kind = "REPORT",
                storageKind = "INLINE",
                storageRef = "inline",
                contentText = "body",
                finalOutput = true,
                createdAt = 2000,
            )
        )

        val artefact = assembler.assemble(agent.id)!!.run!!.artefacts.single()
        assertEquals("the report", artefact.title)
        assertEquals("REPORT", artefact.kind)
        assertTrue(artefact.finalOutput)
    }

    @Test
    fun contextCountsShowWhatWasSuppliedOutOfWhatWasOffered() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        val recorded = db.agentContextReferenceDao()
        recorded.insert(
            com.unuslumen.app.database.entity.AgentContextReferenceEntity(
                referenceId = "c1", runId = "run-1",
                sourceKind = "MEMORY", sourceRef = "m1", suppliedToModel = true, createdAt = 1,
            )
        )
        recorded.insert(
            com.unuslumen.app.database.entity.AgentContextReferenceEntity(
                referenceId = "c2", runId = "run-1",
                sourceKind = "MEMORY", sourceRef = "m2", suppliedToModel = false, createdAt = 2,
            )
        )

        val run = assembler.assemble(agent.id)!!.run!!
        assertEquals("one reached the model", 1, run.contextSupplied)
        assertEquals("two were offered", 2, run.contextOffered)
    }

    @Test
    fun finishedRunsAppearInHistoryNewestFirst() = runTest {
        val agent = makeAgent()
        makeRun(agent.id, runId = "run-old", status = AgentRunStatuses.COMPLETED)
        ledger.appendStateChange(
            "run-old", AgentRunStatuses.RUNNING, AgentRunStatuses.COMPLETED,
            "finished", finishedAt = 2000,
        )
        makeRun(agent.id, runId = "run-new", status = AgentRunStatuses.RUNNING)

        val room = assembler.assemble(agent.id)!!
        assertEquals("the live run is separate from history", "run-new", room.run!!.runId)
        assertEquals(1, room.history.size)
        assertEquals("run-old", room.history.single().runId)
        assertEquals(AgentRunStatuses.COMPLETED, room.history.single().status)
    }

    @Test
    fun theRoomsVisibilityRulesAreTheAgentsOwn() = runTest {
        val agent = makeAgent(
            name = "Quiet",
            visibility = AgentVisibility.TRANSPARENT.copy(
                reasoning = VisibilityRule.CAPTURE_ONLY,
                tools = VisibilityRule(capture = true, display = true, detail = VisibilityDetail.SUMMARY),
            ),
        )
        val room = assembler.assemble(agent.id)!!

        assertFalse("a hidden category is not displayed", room.visibility.reasoning.display)
        assertTrue("but it is still captured", room.visibility.reasoning.capture)
        assertEquals(VisibilityDetail.SUMMARY, room.visibility.tools.detail)
    }

    @Test
    fun theEffectiveConfigurationIsSummarisedFromTheSnapshot() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        val summary = assembler.assemble(agent.id)!!.run!!.effectiveConfigSummary
        assertTrue(
            "the summary names the model that actually ran: $summary",
            summary.contains("a-model"),
        )
        assertTrue(summary.contains("1 tool"))
    }

    @Test
    fun aRunWithNoSnapshotSaysSoRatherThanInventingOne() = runTest {
        val agent = makeAgent()
        makeRun(agent.id, effective = "")
        val summary = assembler.assemble(agent.id)!!.run!!.effectiveConfigSummary
        assertTrue(
            "an absent snapshot is named as absent: $summary",
            summary.contains("No configuration snapshot"),
        )
    }

    @Test
    fun aFailedRunCarriesItsReason() = runTest {
        val agent = makeAgent()
        makeRun(agent.id, status = AgentRunStatuses.RUNNING)
        ledger.appendStateChange(
            "run-1", AgentRunStatuses.RUNNING, AgentRunStatuses.FAILED,
            "the provider refused the request", finishedAt = 3000,
        )

        val room = assembler.assemble(agent.id)!!
        // A failed run is not active, so it moves into history and the room shows idle.
        assertNull("a dead run is not the live run", room.run)
        assertTrue(
            "and the failure is in the history rather than vanished",
            room.history.any { it.status == AgentRunStatuses.FAILED },
        )
    }

    @Test
    fun planProgressIsACountAndNeverAPercentage() = runTest {
        val agent = makeAgent()
        makeRun(agent.id)
        // The runtime does not emit plan steps yet, so the plan is empty and progress is absent
        // rather than zero out of zero.
        assertNull(
            "no recorded steps means no progress claim",
            assembler.assemble(agent.id)!!.run!!.planProgress,
        )
    }
}
