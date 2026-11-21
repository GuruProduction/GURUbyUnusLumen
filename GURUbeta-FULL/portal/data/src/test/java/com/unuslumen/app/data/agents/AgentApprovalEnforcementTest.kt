// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.AgentApprovalEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.agents.AgentMessageAuthors
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
 * The approval system in the way of an action, rather than beside it.
 *
 * THE DEFECT THIS CLOSES WAS NOT A BUG IN THE APPROVAL CODE. Requests could be created, stored,
 * decided, recorded and read back, and every tool call ran regardless, because nothing consulted any
 * of it. A correct system nobody calls is worse than no system, because it looks like a guarantee.
 *
 * THESE TESTS DRIVE THE BROKER'S REAL QUERIES, which is what the runtime's gate calls. A unit test of
 * the gate alone would prove the decision logic and not the link between the decision and the stored
 * rows, and the link is where the gap was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentApprovalEnforcementTest {

    private object PlainTextProtector : DataProtector {
        override fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite =
            ProtectedWrite.Sealed(plaintext)
        override fun open(context: ProtectionContext, stored: String?): ProtectedRead =
            if (stored == null) ProtectedRead.Absent("none") else ProtectedRead.Value(stored)
        override fun isEnvelope(stored: String?): Boolean = false
    }

    private lateinit var db: guruDatabase
    private lateinit var ledger: AgentRunLedger
    private lateinit var broker: AgentInteractionBroker

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
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "conv-1", title = "t", createdDate = 1, updatedDate = 1, messageCount = 0)
        )
        ledger.createRun(
            AgentRunEntity(
                runId = "run-1",
                conversationId = "conv-1",
                agentId = "agent-1",
                agentName = "Sender",
                assignment = "send the thing",
                status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you send",
                toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen",
                modelName = "m",
                createdAt = 1,
                updatedAt = 1,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun anActionWithNoApprovalIsNotGranted() = runTest {
        /*
         * THE GATE'S FIRST QUESTION, ANSWERED FROM THE DATABASE. Nothing is granted, so the gate asks
         * rather than proceeding. Before this, nothing asked anything.
         */
        assertNull(broker.grantedApprovalFor("run-1", "sendSms", "sendSms|{}"))
    }

    @Test
    fun anApprovedActionIsGranted() = runTest {
        val approval = broker.requestApproval(
            runId = "run-1",
            action = "sendSms",
            what = "Send it",
            why = "Needed",
            affected = "One message",
            reversible = false,
            consequences = "Cannot be recalled",
            requestedPermission = "approval.external.sms",
            fingerprintSeed = "sendSms|{}",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)

        val granted = broker.grantedApprovalFor("run-1", "sendSms", "sendSms|{}")
        assertNotNull("a granted approval is found", granted)
        assertEquals(approval.approvalId, granted!!.approvalId)
    }

    @Test
    fun aGrantedApprovalDoesNotCoverDifferentArguments() = runTest {
        /*
         * THE FINGERPRINT, PROVED AT THE QUERY THE GATE USES. A yes for sending one message must not
         * authorise sending a different one, so the gate's lookup includes the arguments.
         */
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send to the plumber", "why", "affected", false,
            "consequences", "perm", "sendSms|{\"to\":\"plumber\"}",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)

        assertNotNull(
            "the exact action is covered",
            broker.grantedApprovalFor("run-1", "sendSms", "sendSms|{\"to\":\"plumber\"}"),
        )
        assertNull(
            "the same tool with a different target is not",
            broker.grantedApprovalFor("run-1", "sendSms", "sendSms|{\"to\":\"someone-else\"}"),
        )
    }

    @Test
    fun aRejectedApprovalGrantsNothing() = runTest {
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = false, decidedBy = AgentMessageAuthors.USER)

        assertNull(
            "a refused action stays refused",
            broker.grantedApprovalFor("run-1", "sendSms", "seed"),
        )
    }

    @Test
    fun aPendingApprovalIsFoundSoTheGateCanBlockRatherThanAskAgain() = runTest {
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!

        val pending = broker.pendingApprovalFor("run-1", "sendSms", "seed")
        assertNotNull("the outstanding question is visible", pending)
        assertEquals(approval.approvalId, pending!!.approvalId)
        assertNull(
            "and it is not a grant, because nobody has said yes",
            broker.grantedApprovalFor("run-1", "sendSms", "seed"),
        )
    }

    @Test
    fun consumingAGrantStopsItAuthorisingAnythingElse() = runTest {
        /*
         * ONE YES, ONE ACTION. An approval that stayed approved would authorise every future send of
         * the same kind, and the owner would have said yes once for an unbounded number of actions.
         */
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)
        assertNotNull(broker.grantedApprovalFor("run-1", "sendSms", "seed"))

        broker.consumeApproval(approval.approvalId)

        assertNull(
            "a spent grant authorises nothing further",
            broker.grantedApprovalFor("run-1", "sendSms", "seed"),
        )
        val spent = db.agentApprovalDao().approval(approval.approvalId)!!
        assertEquals(AgentApprovalEntity.STATE_CANCELLED, spent.state)
        assertTrue(
            "and the row says why it no longer counts: ${spent.decisionNote}",
            spent.decisionNote!!.contains("spent"),
        )
    }

    @Test
    fun consumingAGrantLeavesItsDecisionRecorded() = runTest {
        /*
         * A SPENT APPROVAL IS NOT A DELETED ONE. The record of who said yes and when survives, so a
         * question about why an action ran is still answerable afterwards.
         */
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)
        broker.consumeApproval(approval.approvalId)

        val row = db.agentApprovalDao().approval(approval.approvalId)
        assertNotNull("the row survives being spent", row)
        assertNotNull("and it still says who approved it", row!!.decidedBy)
    }

    @Test
    fun aGrantForOneRunDoesNotAuthoriseAnother() = runTest {
        /*
         * APPROVALS ARE SCOPED TO THEIR RUN. A grant made while one job was running must not carry
         * into a new job, because the new job is work the person has not seen.
         */
        ledger.createRun(
            AgentRunEntity(
                runId = "run-2", conversationId = "conv-1", agentId = "agent-1",
                agentName = "Sender", assignment = "another job", status = AgentRunEntity.STATUS_RUNNING,
                agentDefinitionSnapshotJson = """{"schemaVersion":1}""",
                resolvedSystemPrompt = "you send", toolManifestSnapshotJson = """{"schemaVersion":1}""",
                modelProvider = "UnusLumen", modelName = "m", createdAt = 2, updatedAt = 2,
            )
        )
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)

        assertNotNull("covered in its own run", broker.grantedApprovalFor("run-1", "sendSms", "seed"))
        assertNull(
            "and not in another",
            broker.grantedApprovalFor("run-2", "sendSms", "seed"),
        )
    }

    @Test
    fun requestingApprovalMovesTheRunToWaiting() = runTest {
        /*
         * THE STATE THAT MAKES A HOLD VISIBLE. A run blocked on an approval that still says RUNNING
         * would be interrupted by the recovery scan for no reason, and a person would never know why
         * the work stopped.
         */
        broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )
        assertEquals(
            AgentRunEntity.STATUS_WAITING_FOR_USER,
            ledger.run("run-1")!!.status,
        )
    }

    @Test
    fun approvingResumesTheRunSoTheWorkCanCarryOn() = runTest {
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = true, decidedBy = AgentMessageAuthors.USER)
        assertEquals(
            "a granted approval lets the run continue",
            AgentRunEntity.STATUS_RUNNING,
            ledger.run("run-1")!!.status,
        )
    }

    @Test
    fun aRejectedApprovalLeavesTheRunWaitingForTheAgentToBeTold() = runTest {
        val approval = broker.requestApproval(
            "run-1", "sendSms", "Send", "why", "affected", false, "consequences", "perm", "seed",
        )!!
        broker.decide(approval.approvalId, approved = false, decidedBy = AgentMessageAuthors.USER)
        assertEquals(
            "the run stays waiting, because the agent has to be told the answer",
            AgentRunEntity.STATUS_WAITING_FOR_USER,
            ledger.run("run-1")!!.status,
        )
        assertFalse("and nothing was granted", broker.grantedApprovalFor("run-1", "sendSms", "seed") != null)
    }
}
