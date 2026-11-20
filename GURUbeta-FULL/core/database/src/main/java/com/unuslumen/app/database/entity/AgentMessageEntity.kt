// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One actual communication between participants in an agent's work.
 *
 * WHY THIS IS A TABLE AND NOT AN EVENT PAYLOAD. The event ledger is append-only and ordered, and
 * that is right for what happened. A conversation is a different question: it needs to be read in
 * order as a conversation, filtered by participant, correlated into a reply chain, and updated with
 * a delivery state as the thing travels. Trying to answer those from an event stream means scanning
 * every event of a run and reassembling, every time somebody opens the room.
 *
 * THE FOUR PARTICIPANTS ARE NOT INTERCHANGEABLE. A message from the agent must never be rendered as
 * though GURU wrote it, which is the specification's section 2.5 rule, and the only way to keep that
 * honest is for authorship to be a stored field rather than something the UI infers from position.
 *
 * [sequenceNumber] IS PER RUN, NOT PER CONVERSATION. A run's messages are the unit that is read
 * together, and a per-conversation sequence would force every run to coordinate with every other
 * run in the same conversation for a number that only ever matters within one.
 *
 * THE ASSIGNMENT IS NOT HERE. The original assignment lives on the run row and is immutable.
 * An amendment is a message of type [MESSAGE_TYPE_ASSIGNMENT_AMENDMENT], so changing course is
 * recorded as a thing somebody said at a time, and the original brief is still the brief.
 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["run_id", "sequence_number"],
            name = "index_agent_messages_run_id_sequence",
            unique = true,
        ),
        Index(value = ["run_id", "created_at"], name = "index_agent_messages_run_id_created_at"),
        Index(value = ["run_id", "author_type"], name = "index_agent_messages_run_id_author"),
        Index(value = ["correlation_id"], name = "index_agent_messages_correlation_id"),
        Index(value = ["delivery_state"], name = "index_agent_messages_delivery_state"),
    ],
)
data class AgentMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    /** One-based within the run. Allocated transactionally, the same rule as the event ledger. */
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Long,

    /** Who wrote it. One of the AUTHOR_ constants, never a display name. */
    @ColumnInfo(name = "author_type")
    val authorType: String,

    /** Which specific agent, when the author is an agent. Null for GURU, the user and the system. */
    @ColumnInfo(name = "author_id")
    val authorId: String? = null,

    /** Who it is for. One of the RECIPIENT_ constants, never a display name. */
    @ColumnInfo(name = "recipient_type")
    val recipientType: String,

    @ColumnInfo(name = "recipient_id")
    val recipientId: String? = null,

    /** What kind of communication this is. One of the MESSAGE_TYPE_ constants. */
    @ColumnInfo(name = "message_type")
    val messageType: String,

    @ColumnInfo(name = "content")
    val content: String,

    /**
     * Ties a question to its answer, and a message to the thing it replies to.
     *
     * A REPLY IS NOT A SECOND CONVERSATION. Without this, an agent's question and GURU's answer are
     * two adjacent rows and nothing more, so a reader cannot follow one thread through a run that
     * asked several things.
     */
    @ColumnInfo(name = "correlation_id")
    val correlationId: String? = null,

    /**
     * How far the message has got.
     *
     * THE DISTINCTION THE SPECIFICATION ASKS FOR: a message that was queued behind a running tool
     * is not the same as one the other side has processed, and collapsing them would claim delivery
     * that did not happen. One of the DELIVERY_ constants.
     */
    @ColumnInfo(name = "delivery_state", defaultValue = "QUEUED")
    val deliveryState: String = DELIVERY_QUEUED,

    /** Why it failed, when the state is FAILED. Bounded and sanitised at the write site. */
    @ColumnInfo(name = "delivery_failure_reason")
    val deliveryFailureReason: String? = null,

    /** How urgent the author considered it. Used to decide whether it may interrupt. */
    @ColumnInfo(name = "urgency", defaultValue = "NORMAL")
    val urgency: String = "NORMAL",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** When the other side actually took it. Null until it did. */
    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    /** When it entered the model's context, when it did. Null when it never did. */
    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long? = null,
) {
    companion object {
        // ---- authorship ----
        const val AUTHOR_USER = AgentRunEventEntity.ACTOR_USER
        const val AUTHOR_GURU = AgentRunEventEntity.ACTOR_GURU
        const val AUTHOR_AGENT = AgentRunEventEntity.ACTOR_AGENT
        const val AUTHOR_SYSTEM = AgentRunEventEntity.ACTOR_SYSTEM

        // ---- recipients ----
        const val RECIPIENT_GURU = AgentRunEventEntity.ACTOR_GURU
        const val RECIPIENT_USER = AgentRunEventEntity.ACTOR_USER
        const val RECIPIENT_AGENT = AgentRunEventEntity.ACTOR_AGENT
        const val RECIPIENT_SYSTEM = AgentRunEventEntity.ACTOR_SYSTEM

        // ---- kinds of communication ----
        /** The original brief. Present only as a reference; the run row holds the authoritative copy. */
        const val MESSAGE_TYPE_ASSIGNMENT = "ASSIGNMENT"

        /** A later instruction. The original assignment is untouched; this is what changed. */
        const val MESSAGE_TYPE_ASSIGNMENT_AMENDMENT = "ASSIGNMENT_AMENDMENT"

        const val MESSAGE_TYPE_QUESTION = "QUESTION"
        const val MESSAGE_TYPE_ANSWER = "ANSWER"
        const val MESSAGE_TYPE_CLARIFICATION = "CLARIFICATION"
        const val MESSAGE_TYPE_INSTRUCTION = "INSTRUCTION"
        const val MESSAGE_TYPE_FINDING = "FINDING"
        const val MESSAGE_TYPE_EVIDENCE = "EVIDENCE"
        const val MESSAGE_TYPE_BLOCKER = "BLOCKER"
        const val MESSAGE_TYPE_PROGRESS_REQUEST = "PROGRESS_REQUEST"
        const val MESSAGE_TYPE_COMPLETION = "COMPLETION"
        const val MESSAGE_TYPE_ERROR = "ERROR"
        const val MESSAGE_TYPE_CANCELLATION = "CANCELLATION"
        const val MESSAGE_TYPE_APPROVAL_REQUEST = "APPROVAL_REQUEST"
        const val MESSAGE_TYPE_APPROVAL_DECISION = "APPROVAL_DECISION"

        /**
         * A stage of the work is finished, reported before the whole job is.
         *
         * A MILESTONE IS NOT AN EARLY COMPLETION. COMPLETION means the assignment is done and the
         * report is the answer; a milestone means the agent is still working and has something worth
         * saying now. Collapsing them would make a room unable to tell a finished run from a chatty
         * one, and would let a milestone be read as the final report.
         */
        const val MESSAGE_TYPE_MILESTONE = "MILESTONE"

        /**
         * Something the agent noticed that could go wrong.
         *
         * NOT AN ERROR. An ERROR means something failed; a risk means something has not failed and
         * might. Reporting a risk as an error would tell the person the work broke when it did not.
         */
        const val MESSAGE_TYPE_RISK = "RISK"

        /** The agent wants the person to look at what it produced before it builds on it. */
        const val MESSAGE_TYPE_REVIEW_REQUEST = "REVIEW_REQUEST"

        // ---- delivery ----
        /** Written, not yet picked up. A message queued behind a running tool lives here. */
        const val DELIVERY_QUEUED = "QUEUED"

        /** The other side has it. */
        const val DELIVERY_DELIVERED = "DELIVERED"

        /** It reached the model's context and influenced the work. */
        const val DELIVERY_PROCESSED = "PROCESSED"

        /** It could not be handed over. The reason says why. */
        const val DELIVERY_FAILED = "FAILED"

        /** Every kind that a person can send to an agent. */
        val USER_SENDABLE_TYPES: Set<String> = setOf(
            MESSAGE_TYPE_QUESTION,
            MESSAGE_TYPE_INSTRUCTION,
            MESSAGE_TYPE_CLARIFICATION,
            MESSAGE_TYPE_ASSIGNMENT_AMENDMENT,
            MESSAGE_TYPE_PROGRESS_REQUEST,
            MESSAGE_TYPE_EVIDENCE,
            MESSAGE_TYPE_APPROVAL_DECISION,
        )
    }
}
