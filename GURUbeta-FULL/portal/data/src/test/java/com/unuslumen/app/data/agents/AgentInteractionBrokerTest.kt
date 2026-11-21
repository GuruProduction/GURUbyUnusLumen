// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.security.DataProtector
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import com.unuslumen.app.domain.security.ProtectionContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The broker, proved on a real database rather than on a mock.
 *
 * EVERY CLAIM HERE IS ABOUT WHAT REACHES SQLITE. Ordering, authorship, delivery states and the
 * approval fingerprint are all facts a mock cannot verify: it would assert that the broker called a
 * method, which passes while the transaction is wrong. The tests use a pass-through protector
 * because the sealing machinery has its own tests and a keystore is not available here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentInteractionBrokerTest {

    /**
     * A protector that stores plaintext and says so.
     *
     * DELIBERATELY HONEST ABOUT WHAT IT IS. The real protector seals values and refuses a write it
     * cannot seal; that behaviour is proved in the security tests. Here the subject is the
     * conversation, so a pass-through keeps the assertions about messages rather than about
     * envelopes. It is named so nobody mistakes this for proof that sealing works.
     */
    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("nothing stored") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var broker: AgentInteractionBroker
    private lateinit var ledger: AgentRunLedger
    private val conversationId = "conv-1"

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
        seedRun("run-1")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Seed a run the way production does: its conversation first.
     *
     * `agent_runs.conversation_id` is a real foreign key, so a run without a conversation is
     * refused by SQLite. That is correct behaviour and the test must respect it rather than
     * disable foreign keys to make a shortcut work: a run genuinely belongs to the conversation
     * that asked for it, and the constraint is what makes deleting that conversation take the run
     * and its whole history with it.
     */
    private suspend fun seedRun(runId: String, conversation: String = conversationId) {
        db.conversationDao().upsertConversation(
            com.unuslumen.app.database.entity.ConversationEntity(
                id = conversation,
                title = "a conversation",
                createdDate = 1000,
                updatedDate = 1000,
                messageCount = 0,
            )
        )
        ledger.createRun(
            AgentRunEntity(
                runId = runId,
                conversationId = conversation,
                agentId = "agent-1",
                agentName = "Researcher",
                assignment = "find the thing",
                status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you research",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "a-model",
                createdAt = 1000,
                updatedAt = 1000,
            )
        )
    }

    @Test
    fun aMessageFromTheAgentIsNotAttributedToGuru() = runTest {
        /*
         * THE SPECIFICATION'S SECTION 2.5 RULE, PROVED AT THE STORAGE LAYER. An agent's message must
         * never be presented as though GURU wrote it, and the only way to keep that honest is for
         * authorship to be a stored field rather than something a surface infers from position.
         */
        val message = broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageEntity.AUTHOR_AGENT,
                authorId = "agent-1",
                recipientType = AgentMessageEntity.RECIPIENT_GURU,
                messageType = AgentMessageEntity.MESSAGE_TYPE_FINDING,
                content = "I found three candidates.",
            ),
        )

        assertNotNull(message)
        assertEquals(AgentMessageEntity.AUTHOR_AGENT, message!!.authorType)
        assertEquals("agent-1", message.authorId)
        assertEquals(AgentMessageEntity.RECIPIENT_GURU, message.recipientType)
        assertNotEquals(
            "an agent's message must never be stored under GURU's authorship",
            AgentMessageEntity.AUTHOR_GURU,
            message.authorType,
        )
    }

    @Test
    fun messagesAreOrderedPerRunAndStartAtOne() = runTest {
        val first = broker.send("run-1", userMessage("first"))
        val second = broker.send("run-1", userMessage("second"))
        val third = broker.send("run-1", userMessage("third"))

        assertEquals(1L, first!!.sequenceNumber)
        assertEquals(2L, second!!.sequenceNumber)
        assertEquals(3L, third!!.sequenceNumber)

        val conversation = broker.conversation("run-1")
        assertEquals(
            listOf("first", "second", "third"),
            conversation.map { it.content },
        )
    }

    @Test
    fun aSecondRunStartsItsOwnSequence() = runTest {
        seedRun("run-2")
        broker.send("run-1", userMessage("a"))
        broker.send("run-1", userMessage("b"))
        val secondRunFirst = broker.send("run-2", userMessage("c"))

        assertEquals(
            "sequence is per run, so a second run starts at one rather than continuing",
            1L,
            secondRunFirst!!.sequenceNumber,
        )
    }

    @Test
    fun aNewMessageIsQueuedRatherThanClaimedDelivered() = runTest {
        /*
         * THE DISTINCTION THE SPECIFICATION ASKS FOR. "Sent" must not mean "typed". A message
         * written while a tool is running has reached nothing, and recording it as delivered would
         * tell a person their instruction had been seen when it was sitting in a queue.
         */
        val message = broker.send("run-1", userMessage("do this next"))!!
        assertEquals(AgentMessageEntity.DELIVERY_QUEUED, message.deliveryState)
        assertNull("nothing has taken it, so it has no delivery time", message.deliveredAt)
    }

    @Test
    fun deliveryAndProcessingAreTwoDifferentStates() = runTest {
        val message = broker.send("run-1", userMessage("steer left"))!!

        broker.markDelivered(message.messageId, "run-1")
        val delivered = broker.conversation("run-1").single()
        assertEquals(AgentMessageEntity.DELIVERY_DELIVERED, delivered.deliveryState)
        assertNotNull("delivery has a time", delivered.deliveredAt)
        assertNull("but the model has not read it yet", delivered.consumedAt)

        broker.markProcessed(message.messageId)
        val processed = broker.conversation("run-1").single()
        assertEquals(AgentMessageEntity.DELIVERY_PROCESSED, processed.deliveryState)
        assertNotNull("and now it has a time it entered the context", processed.consumedAt)
    }

    @Test
    fun aFailedDeliveryIsRecordedWithItsReasonAndIsNotSilent() = runTest {
        val message = broker.send("run-1", userMessage("never arrives"))!!
        broker.markFailed(message.messageId, "run-1", "the run had already finished")

        val stored = broker.conversation("run-1").single()
        assertEquals(AgentMessageEntity.DELIVERY_FAILED, stored.deliveryState)
        assertEquals("the run had already finished", stored.deliveryFailureReason)
    }

    @Test
    fun pendingForReturnsOnlyWhatHasNotBeenProcessed() = runTest {
        val first = broker.send("run-1", userMessage("one"))!!
        val second = broker.send("run-1", userMessage("two"))!!
        broker.send("run-1", userMessage("three"))!!

        broker.markDelivered(first.messageId, "run-1")
        broker.markProcessed(first.messageId)

        val pending = broker.pendingFor("run-1", AgentMessageEntity.RECIPIENT_AGENT)
        assertEquals(
            "a processed message is not pending; a queued one is",
            listOf("two", "three"),
            pending.map { it.content },
        )
        assertTrue(pending.none { it.messageId == first.messageId })
        assertTrue(pending.any { it.messageId == second.messageId })
    }

    @Test
    fun pendingForIsScopedToTheRecipient() = runTest {
        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageEntity.AUTHOR_AGENT,
                recipientType = AgentMessageEntity.RECIPIENT_USER,
                messageType = AgentMessageEntity.MESSAGE_TYPE_FINDING,
                content = "for the user",
            ),
        )
        broker.send("run-1", userMessage("for the agent"))

        val forAgent = broker.pendingFor("run-1", AgentMessageEntity.RECIPIENT_AGENT)
        assertEquals(listOf("for the agent"), forAgent.map { it.content })

        val forUser = broker.pendingFor("run-1", AgentMessageEntity.RECIPIENT_USER)
        assertEquals(listOf("for the user"), forUser.map { it.content })
    }

    @Test
    fun anAnswerIsCorrelatedToOneQuestion() = runTest {
        val question = broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageEntity.AUTHOR_AGENT,
                authorId = "agent-1",
                recipientType = AgentMessageEntity.RECIPIENT_GURU,
                messageType = AgentMessageEntity.MESSAGE_TYPE_QUESTION,
                content = "which branch?",
                correlationId = "thread-a",
            ),
        )!!
        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageEntity.AUTHOR_GURU,
                recipientType = AgentMessageEntity.RECIPIENT_AGENT,
                recipientId = "agent-1",
                messageType = AgentMessageEntity.MESSAGE_TYPE_ANSWER,
                content = "the second one",
                correlationId = "thread-a",
            ),
        )

        assertEquals("thread-a", question.correlationId)
    }

    @Test
    fun aMessageToARunThatDoesNotExistIsNotRecorded() = runTest {
        val result = broker.send("no-such-run", userMessage("hello"))
        assertNull("a message to no run is not a message", result)
    }

    @Test
    fun theAssignmentIsNeverRewrittenByAnAmendment() = runTest {
        /*
         * THE SPECIFICATION'S SECTION 5.2. The original assignment is immutable; a later
         * instruction is an amendment recorded as its own message with its author and time. The
         * run row's assignment is untouched, which is what makes "the brief is still the brief"
         * true rather than a comment.
         */
        val originalAssignment = ledger.run("run-1")!!.assignment
        assertEquals("find the thing", originalAssignment)

        broker.send(
            "run-1",
            AgentInteractionBroker.Outgoing(
                authorType = AgentMessageEntity.AUTHOR_USER,
                recipientType = AgentMessageEntity.RECIPIENT_AGENT,
                messageType = AgentMessageEntity.MESSAGE_TYPE_ASSIGNMENT_AMENDMENT,
                content = "actually look at the other thing instead",
            ),
        )

        assertEquals(
            "the amendment must not overwrite the original brief",
            "find the thing",
            ledger.run("run-1")!!.assignment,
        )
        val amendment = broker.conversation("run-1").single()
        assertEquals(AgentMessageEntity.MESSAGE_TYPE_ASSIGNMENT_AMENDMENT, amendment.messageType)
        assertEquals(AgentMessageEntity.AUTHOR_USER, amendment.authorType)
    }

    // ------------------------------------------------------------------
    // Approvals
    // ------------------------------------------------------------------

    @Test
    fun anApprovalRequestCarriesEveryFactAPersonNeedsToDecide() = runTest {
        val approval = broker.requestApproval(
            runId = "run-1",
            action = "sendSms",
            what = "Send a message to the plumber",
            why = "You asked me to arrange the repair",
            affected = "One SMS to +440000000000, billed to your account",
            reversible = false,
            consequences = "The message leaves the phone and cannot be recalled",
            requestedPermission = "approval.external.sms",
            fingerprintSeed = "sendSms|plumber|hello",
        )!!

        assertEquals(AgentApprovalEntityState.PENDING, approval.state)
        assertEquals("sendSms", approval.action)
        assertTrue(approval.what.isNotBlank())
        assertTrue(approval.why.isNotBlank())
        assertTrue(approval.affected.isNotBlank())
        assertTrue(approval.consequences.isNotBlank())
        assertFalse("an SMS cannot be recalled, and it says so", approval.reversible)
        assertEquals("approval.external.sms", approval.requestedPermission)
        assertTrue("the fingerprint is a hash, not the action itself", approval.actionFingerprint.length == 64)
    }

    @Test
    fun requestingApprovalPutsTheRunIntoWaitingForTheUser() = runTest {
        broker.requestApproval(
            runId = "run-1",
            action = "publish",
            what = "Publish the artefact",
            why = "It is finished",
            affected = "The public listing",
            reversible = true,
            consequences = "It becomes visible to others",
            requestedPermission = "approval.artefact.publish",
            fingerprintSeed = "publish|artefact-1",
        )

        assertEquals(
            "a run blocked on a person must say so, or the recovery scan interrupts it for no reason",
            AgentRunEntity.STATUS_WAITING_FOR_USER,
            ledger.run("run-1")!!.status,
        )
    }

    @Test
    fun decidingAnApprovalRecordsTheDecisionAndItsAuthor() = runTest {
        val approval = broker.requestApproval(
            runId = "run-1",
            action = "sendSms",
            what = "Send it",
            why = "Needed",
            affected = "One message",
            reversible = false,
            consequences = "Cannot be recalled",
            requestedPermission = "approval.external.sms",
            fingerprintSeed = "seed",
        )!!

        val decided = broker.decide(
            approvalId = approval.approvalId,
            approved = true,
            decidedBy = AgentMessageEntity.AUTHOR_USER,
            note = "go ahead",
        )!!

        assertEquals(AgentApprovalEntityState.APPROVED, decided.state)
        assertEquals(AgentMessageEntity.AUTHOR_USER, decided.decidedBy)
        assertNotNull(decided.decidedAt)
        assertEquals("go ahead", decided.decisionNote)
    }

    @Test
    fun approvingResumesTheRunAndRejectingDoesNot() = runTest {
        val approved = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences",
            "approval.external.sms", "seed-a",
        )!!
        broker.decide(approved.approvalId, approved = true, decidedBy = AgentMessageEntity.AUTHOR_USER)
        assertEquals(
            "a granted approval lets the work carry on",
            AgentRunEntity.STATUS_RUNNING,
            ledger.run("run-1")!!.status,
        )

        val rejected = broker.requestApproval(
            "run-1", "sendSms", "Send again", "why", "affected", false, "consequences",
            "approval.external.sms", "seed-b",
        )!!
        broker.decide(rejected.approvalId, approved = false, decidedBy = AgentMessageEntity.AUTHOR_USER)
        assertEquals(
            "a rejection leaves the run waiting, because the agent has to be told",
            AgentRunEntity.STATUS_WAITING_FOR_USER,
            ledger.run("run-1")!!.status,
        )
    }

    @Test
    fun aSecondDecisionChangesNothing() = runTest {
        /*
         * TWO TAPS ON YES, OR A TAP RACING A CANCELLATION, MUST PRODUCE ONE DECISION. The update is
         * conditional on the row still being pending, so the second call finds nothing to change and
         * the first outcome stands rather than being overwritten by a different one.
         */
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences",
            "approval.external.sms", "seed",
        )!!

        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageEntity.AUTHOR_USER)
        val second = broker.decide(approval.approvalId, approved = false, decidedBy = AgentMessageEntity.AUTHOR_USER)

        assertEquals(
            "the first decision stands; a rejection after an approval must not overwrite it",
            AgentApprovalEntityState.APPROVED,
            second!!.state,
        )
    }

    @Test
    fun anApprovalCoversOnlyTheActionItWasGrantedFor() = runTest {
        /*
         * THE CHECK THAT STOPS A YES BEING SPENT TWICE ON DIFFERENT THINGS. An approval for one
         * specific send must not authorise a different send, which is the whole reason the
         * fingerprint exists rather than a boolean "may send".
         */
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send to the plumber", "why", "affected", false, "consequences",
            "approval.external.sms", "sendSms|plumber|hello",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageEntity.AUTHOR_USER)

        assertTrue(
            "the exact action it was granted for is covered",
            broker.approvalCovers(approval.approvalId, "sendSms|plumber|hello"),
        )
        assertFalse(
            "the same tool aimed at a different target is NOT covered",
            broker.approvalCovers(approval.approvalId, "sendSms|someone-else|hello"),
        )
        assertFalse(
            "nor the same target with different content",
            broker.approvalCovers(approval.approvalId, "sendSms|plumber|goodbye"),
        )
    }

    @Test
    fun anUndecidedApprovalCoversNothing() = runTest {
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences",
            "approval.external.sms", "seed",
        )!!
        assertFalse(
            "a pending request authorises nothing",
            broker.approvalCovers(approval.approvalId, "seed"),
        )
    }

    @Test
    fun pendingApprovalsListAwaitsADecision() = runTest {
        broker.requestApproval(
            "run-1", "a", "first", "why", "affected", true, "consequences",
            "perm", "seed-1",
        )
        val second = broker.requestApproval(
            "run-1", "b", "second", "why", "affected", true, "consequences",
            "perm", "seed-2",
        )!!
        broker.decide(second.approvalId, approved = true, decidedBy = AgentMessageEntity.AUTHOR_USER)

        val pending = broker.pendingApprovals()
        assertEquals("only the undecided one is pending", 1, pending.size)
        assertEquals("a", pending.single().action)
    }

    @Test
    fun theConversationIsAvailableAsAStream() = runTest {
        broker.send("run-1", userMessage("hello"))
        broker.send("run-1", userMessage("again"))

        val fromFlow = broker.conversationFlow("run-1").first()
        assertEquals(
            "a live room reads the same conversation the one-shot read returns",
            broker.conversation("run-1").map { it.content },
            fromFlow.map { it.content },
        )
        assertEquals(listOf("hello", "again"), fromFlow.map { it.content })
    }

    private fun userMessage(text: String) = AgentInteractionBroker.Outgoing(
        authorType = AgentMessageEntity.AUTHOR_USER,
        recipientType = AgentMessageEntity.RECIPIENT_AGENT,
        messageType = AgentMessageEntity.MESSAGE_TYPE_INSTRUCTION,
        content = text,
    )

    /** The state constants, aliased so the assertions read as the concept rather than the path. */
    private object AgentApprovalEntityState {
        const val PENDING = com.unuslumen.app.database.entity.AgentApprovalEntity.STATE_PENDING
        const val APPROVED = com.unuslumen.app.database.entity.AgentApprovalEntity.STATE_APPROVED
        const val REJECTED = com.unuslumen.app.database.entity.AgentApprovalEntity.STATE_REJECTED
    }
}
