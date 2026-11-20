// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.withTransaction
import com.unuslumen.app.database.dao.AgentApprovalDao
import com.unuslumen.app.database.dao.AgentMessageDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentApprovalEntity
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.database.guruDatabase
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The one place a participant says something to another participant.
 *
 * WHAT THIS REPLACES. A conversation between GURU and an agent used to be two adjacent entries in a
 * message list, with authorship implied by position and nothing persisted at all: the list lived in
 * a map and died with the process. That meant an agent could not be asked a question by a person
 * while it worked, an answer could not be traced back to its question, and a statement the agent
 * made could be rendered as though GURU had said it.
 *
 * THREE THINGS THIS GUARANTEES, AND EACH IS A SEPARATE FIELD.
 *
 *  - Authorship is stored, never inferred. A message from an agent carries the agent's identity, so
 *    a surface that draws it cannot accidentally attribute it to GURU.
 *  - Ordering is transactional. The sequence number is allocated inside the same transaction as the
 *    insert, against the run's own counter, so two writers cannot both take four.
 *  - Delivery is a state that moves forward. Queued, delivered, processed and failed are four
 *    different facts, and a message queued behind a running tool is not claimed as delivered.
 *
 * THE RUN'S ASSIGNMENT IS NOT WRITABLE HERE. An amendment is a message, recorded with its author
 * and time, and the run row's `assignment` is untouched. That is what makes "the original brief is
 * still the brief" true rather than a comment.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class AgentInteractionBroker(
    private val database: guruDatabase,
    private val messageDao: AgentMessageDao,
    private val approvalDao: AgentApprovalDao,
    private val runDao: AgentRunDao,
    private val ledger: AgentRunLedger,
) {

    /** One message, as a caller asks for it to be sent. */
    data class Outgoing(
        val authorType: String,
        val authorId: String? = null,
        val recipientType: String,
        val recipientId: String? = null,
        val messageType: String,
        val content: String,
        val correlationId: String? = null,
        val urgency: String = "NORMAL",
    )

    /**
     * Send a message and record it as an event in the same breath.
     *
     * THE MESSAGE ROW AND THE EVENT ARE WRITTEN TOGETHER, and the message is written first because
     * it is the thing that carries meaning. The event is the ledger's record that the exchange
     * happened, which is what makes a conversation part of the run's history rather than a side
     * table somebody might not think to read.
     *
     * Returns null when the run does not exist. A message to no run is not a message; recording one
     * would create a conversation with no owner.
     */
    suspend fun send(runId: String, message: Outgoing): AgentMessageEntity? =
        database.withTransaction {
            val run = runDao.run(runId) ?: return@withTransaction null
            val last = messageDao.lastMessage(runId)
            val sequence = (last?.sequenceNumber ?: 0L) + 1
            val now = System.currentTimeMillis()

            val entity = AgentMessageEntity(
                messageId = Uuid.random().toString(),
                runId = runId,
                sequenceNumber = sequence,
                authorType = message.authorType,
                authorId = message.authorId,
                recipientType = message.recipientType,
                recipientId = message.recipientId,
                messageType = message.messageType,
                content = message.content,
                correlationId = message.correlationId,
                // QUEUED, NOT DELIVERED. Nothing has picked it up yet, and claiming delivery at the
                // moment of writing is the defect that makes "sent" mean "typed".
                deliveryState = AgentMessageEntity.DELIVERY_QUEUED,
                urgency = message.urgency,
                createdAt = now,
            )
            messageDao.insert(entity)

            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MESSAGE_CREATED,
                    actorType = message.authorType,
                    actorId = message.authorId,
                    correlationId = message.correlationId,
                    payload = buildJson {
                        put("messageId", entity.messageId)
                        put("sequence", sequence)
                        put("authorType", message.authorType)
                        put("recipientType", message.recipientType)
                        put("messageType", message.messageType)
                        // The content is on the message row. The event carries the identity and the
                        // shape, because an event payload is evidence of an exchange and a
                        // duplicate copy of every word would be a second place for it to drift.
                        put("contentChars", message.content.length)
                    },
                )
            )

            entity
        }

    /**
     * Mark a message as having reached its recipient.
     *
     * SEPARATE FROM [send] BECAUSE A QUEUED MESSAGE IS NOT A DELIVERED ONE. A message the user sent
     * while a tool was running sits at QUEUED until the loop reaches a boundary and takes it, and
     * that difference is exactly what the specification asks the interface to state plainly.
     */
    suspend fun markDelivered(messageId: String, runId: String): Boolean {
        val now = System.currentTimeMillis()
        val changed = database.withTransaction {
            messageDao.markDelivered(messageId, AgentMessageEntity.DELIVERY_DELIVERED, now)
            true
        }
        if (changed) {
            runCatching {
                ledger.append(
                    runId,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MESSAGE_DELIVERED,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                        payload = buildJson {
                            put("messageId", messageId)
                            put("deliveredAt", now)
                        },
                    )
                )
            }
        }
        return changed
    }

    /**
     * Mark a message as having actually entered the model's context.
     *
     * THE THIRD STATE, AND THE ONE THAT MATTERS FOR HONESTY. A message can be delivered to the
     * runtime and never read by the model; this records the difference rather than letting
     * "delivered" imply "influenced the work".
     */
    suspend fun markProcessed(messageId: String) {
        messageDao.markProcessed(messageId, System.currentTimeMillis())
    }

    /** A delivery that could not happen. Reported with a reason, never dropped in silence. */
    suspend fun markFailed(messageId: String, runId: String, reason: String) {
        val bounded = reason.take(500)
        messageDao.markFailed(messageId, bounded)
        runCatching {
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.MESSAGE_DELIVERY_FAILED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                    payload = buildJson {
                        put("messageId", messageId)
                        put("reason", bounded)
                    },
                )
            )
        }
    }

    /**
     * What the user has said to this run that it has not yet processed.
     *
     * THE LOOP CALLS THIS AT ITS SAFE BOUNDARY, not a UI. A steering message is picked up between
     * rounds, when nothing is in flight, so an instruction never arrives mid-tool-call.
     */
    suspend fun pendingFor(runId: String, recipientType: String): List<AgentMessageEntity> =
        messageDao.pendingFor(runId, recipientType)

    /** The whole conversation for one run, in order. */
    suspend fun conversation(runId: String): List<AgentMessageEntity> =
        messageDao.messagesForRun(runId)

    /** The same as a stream, so a live room updates as messages arrive. */
    fun conversationFlow(runId: String): Flow<List<AgentMessageEntity>> =
        messageDao.messagesForRunFlow(runId)

    // ------------------------------------------------------------------
    // Approvals
    // ------------------------------------------------------------------

    /**
     * Ask for permission, with the six facts the specification requires.
     *
     * THE FINGERPRINT IS TAKEN HERE OVER THE EXACT ACTION. A yes authorises one action, and the
     * runtime recomputes the fingerprint before proceeding, so an approval cannot be spent on a
     * different send, a different target or a different payload.
     */
    suspend fun requestApproval(
        runId: String,
        action: String,
        what: String,
        why: String,
        affected: String,
        reversible: Boolean,
        consequences: String,
        requestedPermission: String,
        fingerprintSeed: String,
        now: Long = System.currentTimeMillis(),
    ): AgentApprovalEntity? {
        val approval = database.withTransaction {
            runDao.run(runId) ?: return@withTransaction null
            val entity = AgentApprovalEntity(
                approvalId = Uuid.random().toString(),
                runId = runId,
                action = action,
                what = what,
                why = why,
                affected = affected,
                reversible = reversible,
                consequences = consequences,
                requestedPermission = requestedPermission,
                actionFingerprint = fingerprint(fingerprintSeed),
                state = AgentApprovalEntity.STATE_PENDING,
                requestedAt = now,
            )
            approvalDao.insert(entity)
            entity
        } ?: return null

        // THE RUN IS WAITING ON A PERSON, AND SAYS SO. A run that is blocked on an approval and
        // still shows RUNNING is a run the recovery scan will eventually interrupt for no reason.
        runCatching {
            ledger.appendStateChange(
                runId,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                com.unuslumen.app.database.entity.AgentRunEntity.STATUS_WAITING_FOR_USER,
                "waiting for approval: $what",
            )
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.QUESTION_OPENED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                    payload = buildJson {
                        put("kind", "APPROVAL")
                        put("approvalId", approval.approvalId)
                        put("action", action)
                        put("permission", requestedPermission)
                    },
                )
            )
        }

        return approval
    }

    /**
     * Decide a pending approval.
     *
     * A SECOND DECISION CHANGES NOTHING. The update is conditional on the row still being pending,
     * so two taps — or a tap racing a cancellation — produce one outcome. Returns the resulting row,
     * or null when the approval did not exist or was already decided.
     */
    suspend fun decide(
        approvalId: String,
        approved: Boolean,
        decidedBy: String,
        note: String? = null,
    ): AgentApprovalEntity? {
        val existing = approvalDao.approval(approvalId) ?: return null
        val now = System.currentTimeMillis()
        val target = if (approved) {
            AgentApprovalEntity.STATE_APPROVED
        } else {
            AgentApprovalEntity.STATE_REJECTED
        }
        val changed = approvalDao.decide(approvalId, target, decidedBy, now, note?.take(1_000))
        if (changed != 1) {
            // Already decided. The existing row is the answer; the caller is told the truth rather
            // than being handed a decision it did not make.
            return approvalDao.approval(approvalId)
        }

        runCatching {
            ledger.append(
                existing.runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.QUESTION_ANSWERED,
                    actorType = decidedBy,
                    payload = buildJson {
                        put("kind", "APPROVAL")
                        put("approvalId", approvalId)
                        put("approved", approved)
                        put("note", note.orEmpty())
                    },
                )
            )
            /*
             * THE RUN RESUMES ONLY ON A YES.
             *
             * A rejection leaves the run WAITING_FOR_USER rather than pushing it back to RUNNING,
             * because the agent has to be told the answer and decide what to do about it. Moving it
             * to RUNNING here would report a run as working while nothing was driving it.
             */
            if (approved) {
                ledger.appendStateChange(
                    existing.runId,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_WAITING_FOR_USER,
                    com.unuslumen.app.database.entity.AgentRunEntity.STATUS_RUNNING,
                    "approval granted for ${existing.action}",
                )
            }
        }

        return approvalDao.approval(approvalId)
    }

    /**
     * Decide, returning the domain view.
     *
     * THE SAME DECISION, TWO SHAPES. The entity-returning [decide] is what the tests assert the
     * stored decision on; this is what a UI module calls, because a screen must not need the
     * database module to render a button. The decision logic is not duplicated: this delegates.
     */
    suspend fun decideAndDescribe(
        approvalId: String,
        approved: Boolean,
        decidedBy: String,
        note: String? = null,
    ): com.unuslumen.app.domain.agents.ApprovalView? =
        decide(approvalId, approved, decidedBy, note)?.toApprovalView()

    /**
     * What is waiting on a person. Oldest first, so the oldest is answered first.
     *
     * RETURNS THE DOMAIN VIEW, NOT THE ENTITY. A UI module must be able to ask this question without
     * depending on the database module, and an entity leaking through a public signature is how a
     * screen ends up importing Room to render a button.
     */
    suspend fun pendingApprovals(): List<com.unuslumen.app.domain.agents.ApprovalView> =
        approvalDao.pending().map { it.toApprovalView() }

    /** Everything one run has asked for, as the domain view. */
    suspend fun approvalViewsFor(runId: String): List<com.unuslumen.app.domain.agents.ApprovalView> =
        approvalDao.forRun(runId).map { it.toApprovalView() }

    private fun AgentApprovalEntity.toApprovalView() =
        com.unuslumen.app.domain.agents.ApprovalView(
            approvalId = approvalId,
            action = action,
            what = what,
            why = why,
            affected = affected,
            reversible = reversible,
            consequences = consequences,
            requestedPermission = requestedPermission,
            requestedAt = requestedAt,
        )

    /** Everything one run has asked for. */
    suspend fun approvalsFor(runId: String): List<AgentApprovalEntity> = approvalDao.forRun(runId)

    /**
     * Does this approval cover this exact action?
     *
     * THE CHECK THAT STOPS A YES BEING REUSED. The runtime calls this before acting, with the
     * fingerprint of what it is about to do. A mismatch means the approval was for something else
     * and must not be spent on this.
     */
    suspend fun approvalCovers(approvalId: String, fingerprintSeed: String): Boolean {
        val approval = approvalDao.approval(approvalId) ?: return false
        return approval.state == AgentApprovalEntity.STATE_APPROVED &&
            approval.actionFingerprint == fingerprint(fingerprintSeed)
    }

    private fun fingerprint(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(seed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** A tiny JSON object builder, matching the shape the ledger uses. */
    private fun buildJson(block: JsonScope.() -> Unit): String {
        val map = linkedMapOf<String, Any?>()
        JsonScope(map).block()
        return buildString {
            append('{')
            var first = true
            for ((key, value) in map) {
                if (!first) append(',')
                first = false
                append('"').append(key).append("\":")
                when (value) {
                    null -> append("null")
                    is Number, is Boolean -> append(value.toString())
                    else -> append('"').append(escape(value.toString())).append('"')
                }
            }
            append('}')
        }
    }

    private fun escape(value: String): String = buildString {
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
    }

    private class JsonScope(private val map: MutableMap<String, Any?>) {
        fun put(key: String, value: String?) { map[key] = value }
        fun put(key: String, value: Int) { map[key] = value }
        fun put(key: String, value: Long) { map[key] = value }
        fun put(key: String, value: Boolean) { map[key] = value }
    }
}
