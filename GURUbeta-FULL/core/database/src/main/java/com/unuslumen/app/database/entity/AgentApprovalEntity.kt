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
 * One decision the runtime is waiting for before an action may proceed.
 *
 * WHY A REQUEST IS A RECORD AND NOT A PROMPT SENTENCE. The specification's section 7.2 requires six
 * specific facts to be shown before a person says yes: what the agent wants to do, why, what is
 * affected, whether it is reversible, what the consequences are, and exactly which permission is
 * being asked for. A question written into a chat message cannot carry those; it is prose. This row
 * is the six facts, so the surface that asks always has them and cannot show a Yes button with
 * nothing behind it.
 *
 * A DECISION IS FINAL AND RECORDED. An approval is not a UI state that disappears when the sheet is
 * dismissed: approved, rejected and cancelled are three distinct terminal outcomes, each with a
 * timestamp, so a run that asked and got nothing is distinguishable from one that was refused.
 *
 * [actionFingerprint] EXISTS SO A YES CANNOT BE REUSED. An approval authorises one specific action,
 * not a class of actions. The fingerprint is taken over the action's own values, so an agent that
 * got a yes for "send this message to this address" cannot spend it on a different one.
 */
@Entity(
    tableName = "agent_approvals",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["run_id", "state"], name = "index_agent_approvals_run_id_state"),
        Index(value = ["state", "requested_at"], name = "index_agent_approvals_state_requested_at"),
        Index(value = ["action_fingerprint"], name = "index_agent_approvals_fingerprint"),
    ],
)
data class AgentApprovalEntity(
    @PrimaryKey
    @ColumnInfo(name = "approval_id")
    val approvalId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    /** The tool or capability the approval is for. */
    @ColumnInfo(name = "action")
    val action: String,

    /** What the agent wants to do, in the agent's own request. */
    @ColumnInfo(name = "what")
    val what: String,

    /** Why it wants to. */
    @ColumnInfo(name = "why")
    val why: String,

    /** What data or systems are affected. */
    @ColumnInfo(name = "affected")
    val affected: String,

    /** Whether the action can be undone, stated as the agent understands it. */
    @ColumnInfo(name = "reversible", defaultValue = "0")
    val reversible: Boolean = false,

    /** What could go wrong. */
    @ColumnInfo(name = "consequences")
    val consequences: String,

    /** The exact permission being requested. */
    @ColumnInfo(name = "requested_permission")
    val requestedPermission: String,

    /**
     * A hash over the action's own values, so a yes covers THIS action only.
     *
     * The runtime recomputes it before proceeding and refuses on a mismatch, which is what stops an
     * approval for one send becoming an approval for any send.
     */
    @ColumnInfo(name = "action_fingerprint")
    val actionFingerprint: String,

    /** One of the STATE_ constants. */
    @ColumnInfo(name = "state", defaultValue = "PENDING")
    val state: String = STATE_PENDING,

    /** Who decided, when it was decided. Null while pending. */
    @ColumnInfo(name = "decided_by")
    val decidedBy: String? = null,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long? = null,

    /** Why they decided that, when they said. */
    @ColumnInfo(name = "decision_note")
    val decisionNote: String? = null,

    @ColumnInfo(name = "requested_at")
    val requestedAt: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_APPROVED = "APPROVED"
        const val STATE_REJECTED = "REJECTED"
        const val STATE_CANCELLED = "CANCELLED"

        /** A decision has been made and nothing can change it. */
        val TERMINAL_STATES: Set<String> = setOf(STATE_APPROVED, STATE_REJECTED, STATE_CANCELLED)

        /** The six facts the specification requires before a person can decide. */
        val REQUIRED_FACTS: List<String> = listOf(
            "what", "why", "affected", "reversible", "consequences", "requestedPermission",
        )
    }
}
