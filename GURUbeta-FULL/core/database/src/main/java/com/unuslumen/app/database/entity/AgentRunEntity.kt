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
 * One durable execution of one agent assignment.
 *
 * THIS ROW IS THE THING THAT SURVIVES. Until now an agent run existed only as an
 * `AgentRuntime.Session` in a map in memory, and the class's own comment said so:
 * "Held in memory on purpose. A session is live work, not a record." That was true
 * while nothing needed to outlive the process. It stops being true the moment a
 * person can close the Agent Room, background the app, or have Android kill the
 * process while an agent is mid-job, and the work is supposed to still be there.
 *
 * THREE THINGS LIVE HERE AND THEY ARE NOT THE SAME THING. [assignment] and the two
 * configuration snapshots are what was asked for and what was actually applied, and
 * they are frozen at creation. [status], [lastEventSequence] and the lease columns are
 * the present, and they move. The events in `agent_run_events` are the history, and
 * they never move at all. Collapsing those into one record is how a system ends up
 * unable to answer "what did it actually do" after somebody edits something.
 *
 * [runId] IS ALSO THE SESSION ID. There is no second identifier. The in-memory session
 * is the live representation of this run, not a separate thing with its own name, and
 * a resume after a restart arrives under the same id it left with.
 *
 * WHY [agentId] IS NOT A FOREIGN KEY. An agent can be renamed, rewritten by the
 * publisher, or deleted. Its execution history has to outlive all three, so the run
 * carries its own copy of who ran, under what name, at what version, with what prompt.
 * A foreign key would either block the delete or take the history with it.
 *
 * THE LEASE. [leaseOwner] is a per-process incarnation identifier, generated once per
 * process start, and never a PID: the kernel reuses PIDs, so a reused number would make
 * a dead owner look alive. The foreground service and the recovery worker both write
 * it; a scan interrupts a run only when the owner is gone from the live registry AND
 * the heartbeat is older than the expiry threshold. Either alone is a false positive.
 */
@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["origin_message_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["parent_run_id"],
            onDelete = ForeignKey.SET_NULL
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "created_at"], name = "index_agent_runs_conversation_id_created_at"),
        Index(value = ["status", "updated_at"], name = "index_agent_runs_status_updated_at"),
        Index(value = ["status", "lease_heartbeat_at"], name = "index_agent_runs_status_lease_heartbeat_at"),
        Index(value = ["agent_id", "created_at"], name = "index_agent_runs_agent_id_created_at"),
        Index(value = ["parent_run_id"], name = "index_agent_runs_parent_run_id"),
        Index(value = ["origin_message_id"], name = "index_agent_runs_origin_message_id"),
    ],
)
data class AgentRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id")
    val runId: String,

    /** The conversation that owns this work. Deleting it deletes the run and everything under it. */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** The message that started the assignment, when there was one. Losing it does not lose the run. */
    @ColumnInfo(name = "origin_message_id")
    val originMessageId: String? = null,

    /** Set when one agent delegated to another. Null at the top of a chain. */
    @ColumnInfo(name = "parent_run_id")
    val parentRunId: String? = null,

    @ColumnInfo(name = "agent_id")
    val agentId: String,

    @ColumnInfo(name = "agent_slug", defaultValue = "")
    val agentSlug: String = "",

    @ColumnInfo(name = "agent_name")
    val agentName: String,

    @ColumnInfo(name = "agent_version", defaultValue = "1")
    val agentVersion: Int = 1,

    /** The original assignment, in the caller's words, frozen. Amendments are events, never edits. */
    val assignment: String,

    /** Materialised lifecycle state. Written only alongside the RUN_STATE_CHANGED that caused it. */
    val status: String,

    /** The complete resolved definition as values at execution time, versioned JSON. */
    @ColumnInfo(name = "agent_definition_snapshot_json")
    val agentDefinitionSnapshotJson: String,

    /** What the caller asked for. */
    @ColumnInfo(name = "requested_config_snapshot_json", defaultValue = "{\"schemaVersion\":1}")
    val requestedConfigSnapshotJson: String = "{\"schemaVersion\":1}",

    /** What the runtime actually applied, after filtering and fallback. */
    @ColumnInfo(name = "effective_config_snapshot_json", defaultValue = "{\"schemaVersion\":1}")
    val effectiveConfigSnapshotJson: String = "{\"schemaVersion\":1}",

    /** The exact prompt handed to the model, not a reference to one. */
    @ColumnInfo(name = "resolved_system_prompt")
    val resolvedSystemPrompt: String,

    /** The exact tool definitions and descriptors offered, versioned JSON. */
    @ColumnInfo(name = "tool_manifest_snapshot_json")
    val toolManifestSnapshotJson: String,

    @ColumnInfo(name = "model_provider")
    val modelProvider: String,

    @ColumnInfo(name = "model_name")
    val modelName: String,

    /** Effective parameters. Never credentials. */
    @ColumnInfo(name = "model_config_snapshot_json", defaultValue = "{\"schemaVersion\":1}")
    val modelConfigSnapshotJson: String = "{\"schemaVersion\":1}",

    /**
     * Whether reasoning was being captured when the run started.
     *
     * CAPTURE IS NOT VISIBILITY. This says what was written to the ledger. The separate
     * snapshot below says what the person was shown. A person turning reasoning off in
     * the room does not stop it being recorded, and a run recorded with capture off is
     * honest about that rather than pretending the reasoning never existed.
     */
    @ColumnInfo(name = "reasoning_capture_enabled_at_start", defaultValue = "1")
    val reasoningCaptureEnabledAtStart: Boolean = true,

    @ColumnInfo(name = "reasoning_visibility_snapshot_json", defaultValue = "{\"schemaVersion\":1}")
    val reasoningVisibilitySnapshotJson: String = "{\"schemaVersion\":1}",

    /** The allocation counter. Every event, checkpoints included, advances this. */
    @ColumnInfo(name = "last_event_sequence", defaultValue = "0")
    val lastEventSequence: Long = 0L,

    @ColumnInfo(name = "last_checkpoint_sequence", defaultValue = "0")
    val lastCheckpointSequence: Long = 0L,

    /** Per-process incarnation identifier. Null when nobody is driving this run. */
    @ColumnInfo(name = "lease_owner")
    val leaseOwner: String? = null,

    /** Last heartbeat from that owner. A stale value plus a missing owner is how death is detected. */
    @ColumnInfo(name = "lease_heartbeat_at")
    val leaseHeartbeatAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** Terminal time. Null while running, waiting, paused or interrupted. */
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,

    @ColumnInfo(name = "failure_code")
    val failureCode: String? = null,

    @ColumnInfo(name = "failure_message")
    val failureMessage: String? = null,

    @ColumnInfo(name = "result_summary")
    val resultSummary: String? = null,
) {
    companion object {
        // ---- lifecycle ----
        const val STATUS_CREATED = "CREATED"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_WAITING_FOR_GURU = "WAITING_FOR_GURU"
        const val STATUS_WAITING_FOR_USER = "WAITING_FOR_USER"
        const val STATUS_WAITING_FOR_TOOL = "WAITING_FOR_TOOL"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_INTEGRATING = "INTEGRATING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_LIMIT_REACHED = "LIMIT_REACHED"

        /**
         * Stopped unexpectedly, state recoverable.
         *
         * NOT THE SAME AS FAILED. A run the platform killed did not fail at its job;
         * it stopped being held. Marking it FAILED would tell the person the work was
         * no good when the truth is that the work is still there and can carry on.
         */
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        /** Every state a run can still move out of. The recovery scan looks at exactly these. */
        val NON_TERMINAL_STATUSES: Set<String> = setOf(
            STATUS_CREATED,
            STATUS_STARTING,
            STATUS_RUNNING,
            STATUS_WAITING_FOR_GURU,
            STATUS_WAITING_FOR_USER,
            STATUS_WAITING_FOR_TOOL,
            STATUS_PAUSED,
            STATUS_INTEGRATING,
            STATUS_INTERRUPTED,
        )

        /** States whose time is over. `finished_at` is set, and nothing resumes them. */
        val TERMINAL_STATUSES: Set<String> = setOf(
            STATUS_COMPLETED,
            STATUS_LIMIT_REACHED,
            STATUS_FAILED,
            STATUS_CANCELLED,
        )

        /**
         * States that are waiting on a person or on GURU rather than on a running loop.
         *
         * These RELEASE their lease without becoming INTERRUPTED. A run waiting for an
         * answer is not a run whose owner died; it is a run doing exactly what it was
         * asked to do, and marking it interrupted would be a lie with consequences.
         */
        val WAITING_STATUSES: Set<String> = setOf(
            STATUS_WAITING_FOR_GURU,
            STATUS_WAITING_FOR_USER,
        )

        /** The reserved agent identity a project-scoped agent run records itself under. */
        const val PROJECT_AGENT_ID = "project-agent"
    }
}
