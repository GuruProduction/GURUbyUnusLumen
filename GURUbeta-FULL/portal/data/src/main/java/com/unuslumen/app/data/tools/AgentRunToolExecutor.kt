// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentCancellationService
import com.unuslumen.app.data.agents.AgentInteractionBroker
import com.unuslumen.app.data.agents.AgentRunLedger
import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.database.dao.AgentMessageDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.ToolObservationDao
import com.unuslumen.app.database.entity.AgentRunEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading and governing agent runs.
 *
 * THE SEPARATION THIS MAINTAINS, AND WHY IT IS THE WHOLE POINT. Four tables answer four different
 * questions, and a tool that blurred them is what made `getToolResult` useless:
 *
 *  - `agent_runs` — what was asked, what state it reached, what the final report was.
 *  - `agent_run_events` — what happened, in order.
 *  - `tool_observations` — what a tool ACTUALLY returned, and what the screen looked like.
 *  - `agent_messages` — what was said, by whom.
 *
 * So this reports them as separate lists with their own headings. In particular, a tool invocation
 * is reported with an output ONLY when an observation row exists for it. A completion event saying
 * a call finished is reported as a lifecycle fact, never dressed up as the call's result, because
 * "it ran" and "here is what it returned" are different claims and a reader who cannot tell them
 * apart will believe the wrong one.
 */
class AgentRunToolExecutor(
    private val runDao: AgentRunDao,
    private val ledger: AgentRunLedger,
    private val observations: ToolObservationDao,
    private val messages: AgentMessageDao,
    private val broker: AgentInteractionBroker,
    private val runtime: AgentRuntime,
    private val cancellation: AgentCancellationService,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private companion object {
        /** How many history events one inspection returns unless asked for more. */
        const val DEFAULT_EVENT_LIMIT = 40

        /** How many recent runs a listing shows when finished ones are included. */
        const val FINISHED_RUNS_LIMIT = 15

        /** The most a single field will hold, so one long report cannot crowd out the rest. */
        const val MAX_FIELD_CHARS = 4_000
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AgentRunToolDefinitions.LIST_AGENT_RUNS -> listRuns(args)
            AgentRunToolDefinitions.GET_AGENT_RUN -> getRun(args)
            AgentRunToolDefinitions.SEND_AGENT_MESSAGE -> sendMessage(args)
            AgentRunToolDefinitions.CANCEL_AGENT_RUN -> cancelRun(args)
            AgentRunToolDefinitions.CANCEL_ALL_AGENT_RUNS -> cancelAll(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    private suspend fun listRuns(args: Map<String, Any?>): ToolExecutionResult {
        val includeFinished = (args["includeFinished"] as? Boolean) ?: false
        val agentQuery = (args["agent"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val active = runCatching { runDao.nonTerminalRuns() }.getOrDefault(emptyList())
        val finished = if (includeFinished) recentFinished(agentQuery) else emptyList()

        val matches = { run: AgentRunEntity ->
            agentQuery == null ||
                run.agentName.equals(agentQuery, ignoreCase = true) ||
                run.agentSlug.equals(agentQuery, ignoreCase = true) ||
                run.agentId == agentQuery
        }

        val rows = (active.filter(matches) + finished.filter(matches))
            .distinctBy { it.runId }
            .sortedByDescending { it.updatedAt }

        val result = RunListing(
            success = true,
            activeCount = rows.count { it.status !in AgentRunEntity.TERMINAL_STATUSES },
            runs = rows.map { it.toSummary() },
            note = when {
                rows.isEmpty() && agentQuery != null ->
                    "No runs found for '$agentQuery'. That agent has not run, or has no runs kept."
                rows.isEmpty() ->
                    "No runs are active. Nothing is executing, so there is nothing to supervise."
                rows.none { it.status in AgentRunEntity.TERMINAL_STATUSES } ->
                    "Everything listed is still live unless its status says otherwise."
                else -> null
            },
        )
        return success(result, RunListing.serializer())
    }

    /**
     * The most recent finished runs.
     *
     * NARROWED BY AGENT WHEN THE QUESTION IS, so a listing about one agent does not read the whole
     * history and then throw most of it away.
     */
    private suspend fun recentFinished(agentQuery: String?): List<AgentRunEntity> =
        runCatching {
            if (agentQuery == null) {
                runDao.recentTerminalRuns(FINISHED_RUNS_LIMIT)
            } else {
                runDao.recentTerminalRunsForAgent(agentQuery, FINISHED_RUNS_LIMIT)
            }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // One run in full
    // ------------------------------------------------------------------

    private suspend fun getRun(args: Map<String, Any?>): ToolExecutionResult {
        val runId = (args["runId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error(
                "Missing 'runId'. Use listAgentRuns to find the run you want.",
            )
        val eventLimit = (args["eventLimit"] as? Number)?.toInt()?.coerceIn(1, 500)
            ?: DEFAULT_EVENT_LIMIT

        val stored = runCatching { runDao.run(runId) }.getOrNull()
            ?: return success(
                RunDetail(
                    success = false,
                    runId = runId,
                    error = "There is no run with the id '$runId'. Use listAgentRuns to see the " +
                        "ids that exist.",
                ),
                RunDetail.serializer(),
            )

        // Opened through the ledger, so the assignment and the prompt are the words rather than the
        // envelopes that sit on disk.
        val run = runCatching { ledger.run(runId) }.getOrNull() ?: stored

        val events = runCatching { ledger.events(runId) }.getOrDefault(emptyList())

        /*
         * THE OBSERVATIONS ARE THE ONLY SOURCE OF A TOOL'S REAL OUTPUT.
         *
         * Keyed by invocation id, which is what pairs an observation to the call that produced it.
         * An invocation with no row here is reported as unrecorded, not as an empty result.
         */
        val observed = runCatching { observations.forRun(runId) }
            .getOrDefault(emptyList())
            .associateBy { it.agentInvocationId ?: "" }

        val toolCalls = events
            .filter { it.eventType == com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED }
            .mapNotNull { event ->
                val payload = readObject(event.payloadJson) ?: return@mapNotNull null
                val invocationId = stringOf(payload, "invocationId") ?: return@mapNotNull null
                val tool = stringOf(payload, "tool") ?: "unknown tool"
                val record = observed[invocationId]
                ToolCallRecord(
                    invocationId = invocationId,
                    tool = tool,
                    arguments = stringOf(payload, "arguments").orEmpty().take(MAX_FIELD_CHARS),
                    // The distinction the whole tool exists to keep.
                    outputRecorded = record != null,
                    output = record?.resultSummary?.takeIf { it.isNotBlank() },
                    outputSucceeded = record?.let { it.success == 1 },
                    outputDurationMs = record?.durationMs,
                    at = formatTime(event.occurredAt),
                )
            }

        val conversation = runCatching { messages.messagesForRun(runId) }
            .getOrDefault(emptyList())
            .map {
                MessageRecord(
                    at = formatTime(it.createdAt),
                    author = it.authorType,
                    type = it.messageType,
                    content = it.content.take(MAX_FIELD_CHARS),
                    delivery = it.deliveryState,
                )
            }

        val timeline = events.takeLast(eventLimit).map {
            EventRecord(
                sequence = it.sequenceNumber,
                type = it.eventType,
                at = formatTime(it.occurredAt),
                summary = summariseEvent(it.eventType, it.payloadJson),
            )
        }

        val detail = RunDetail(
            success = true,
            runId = runId,
            agent = run.agentName,
            status = run.status,
            assignment = run.assignment.take(MAX_FIELD_CHARS),
            startedAt = run.startedAt?.let { formatTime(it) },
            finishedAt = run.finishedAt?.let { formatTime(it) },
            finalReport = run.resultSummary?.takeIf { it.isNotBlank() },
            resultSummaryRecorded = run.resultSummary != null,
            toolCalls = toolCalls,
            messages = conversation,
            timeline = timeline,
            timelineNote = if (events.size > timeline.size) {
                "${events.size - timeline.size} earlier events are not shown. The tool calls and " +
                    "the messages above are complete."
            } else null,
        )
        return success(detail, RunDetail.serializer())
    }

    /**
     * One line about what an event says, taken from its own payload.
     *
     * A SUMMARY IS A READING AID, NOT THE RECORD. The full payload stays in the ledger; this exists
     * so a reader scanning forty events can see which one to open. It never invents: an event whose
     * payload carries nothing recognisable is summarised as its own type name and no more.
     */
    private fun summariseEvent(type: String, payloadJson: String): String {
        val payload = readObject(payloadJson) ?: return type
        return when (type) {
            com.unuslumen.app.domain.agents.AgentEventTypes.RUN_STATE_CHANGED -> {
                val from = stringOf(payload, "from").orEmpty()
                val to = stringOf(payload, "to").orEmpty()
                if (to.isBlank()) type else "$from -> $to"
            }
            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_STARTED,
            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_COMPLETED,
            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_FAILED,
            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_REFUSED,
            com.unuslumen.app.database.entity.AgentRunEventEntity.TOOL_CALL_OUTCOME_UNKNOWN,
            -> {
                val tool = stringOf(payload, "tool").orEmpty()
                val reason = stringOf(payload, "reason") ?: stringOf(payload, "error")
                listOfNotNull(type, tool.takeIf { it.isNotBlank() }, reason?.take(160))
                    .joinToString(" · ")
            }
            else -> stringOf(payload, "reason")
                ?: stringOf(payload, "tool")
                ?: type
        }
    }

    // ------------------------------------------------------------------
    // Steering
    // ------------------------------------------------------------------

    private suspend fun sendMessage(args: Map<String, Any?>): ToolExecutionResult {
        val runId = (args["runId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'runId'.")
        val message = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'message'.")

        val run = runCatching { runDao.run(runId) }.getOrNull()
            ?: return success(
                MessageSent(
                    success = false,
                    runId = runId,
                    error = "There is no run with the id '$runId', so nothing was sent.",
                ),
                MessageSent.serializer(),
            )

        // A finished run has nothing reading it, so accepting a message would promise a delivery
        // that cannot happen. That is the same lie as recording a chat message against a dead run.
        if (run.status in AgentRunEntity.TERMINAL_STATUSES) {
            return success(
                MessageSent(
                    success = false,
                    runId = runId,
                    error = "That run is ${run.status.lowercase().replace('_', ' ')} and nothing is " +
                        "reading it, so the message was not sent. It cannot be resumed; give the " +
                        "agent a new job instead.",
                ),
                MessageSent.serializer(),
            )
        }

        val sent = runCatching {
            runtime.steer(
                runId = runId,
                text = message,
                messageType = com.unuslumen.app.database.entity.AgentMessageEntity.MESSAGE_TYPE_INSTRUCTION,
                authorType = com.unuslumen.app.database.entity.AgentMessageEntity.AUTHOR_GURU,
            )
        }.getOrDefault(false)

        return success(
            MessageSent(
                success = sent,
                runId = runId,
                runStatus = run.status,
                error = if (sent) null else "The message could not be recorded, so it was not sent.",
            ),
            MessageSent.serializer(),
        )
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    private suspend fun cancelRun(args: Map<String, Any?>): ToolExecutionResult {
        val runId = (args["runId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'runId'.")
        val reason = (args["reason"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "cancelled by GURU"

        val outcome = runCatching { cancellation.cancel(runId, reason) }
            .getOrElse { failure ->
                return success(
                    CancelOutcome(
                        success = false,
                        runId = runId,
                        sentence = "The cancellation could not be recorded: " +
                            "${failure.message ?: failure.javaClass.simpleName}.",
                    ),
                    CancelOutcome.serializer(),
                )
            }

        return success(
            CancelOutcome(
                success = outcome.fromStatus != null,
                runId = runId,
                fromStatus = outcome.fromStatus,
                stoppedLiveExecution = outcome.stoppedLiveExecution,
                alreadyTerminal = outcome.alreadyTerminal,
                sentence = outcome.sentence,
            ),
            CancelOutcome.serializer(),
        )
    }

    private suspend fun cancelAll(args: Map<String, Any?>): ToolExecutionResult {
        val reason = (args["reason"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "cancelled by GURU, all runs"

        val outcomes = runCatching { cancellation.cancelAll(reason) }
            .getOrElse { failure ->
                return success(
                    CancelAllOutcome(
                        success = false,
                        sentence = "Nothing was cancelled: the run list could not be read " +
                            "(${failure.message ?: failure.javaClass.simpleName}).",
                    ),
                    CancelAllOutcome.serializer(),
                )
            }

        val stopped = outcomes.count { it.fromStatus != null && !it.alreadyTerminal }
        return success(
            CancelAllOutcome(
                success = true,
                stopped = stopped,
                alreadyFinished = outcomes.count { it.alreadyTerminal },
                sentences = outcomes.map { it.sentence },
                sentence = when {
                    outcomes.isEmpty() -> "Nothing was running, so there was nothing to stop."
                    stopped == 0 -> "Nothing was still running; every run had already finished."
                    else -> "Stopped $stopped run${if (stopped == 1) "" else "s"}. " +
                        "Nothing that was running is still running."
                },
            ),
            CancelAllOutcome.serializer(),
        )
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    private fun AgentRunEntity.toSummary() = RunSummary(
        runId = runId,
        agent = agentName,
        status = status,
        assignment = assignment.take(200),
        lastActivity = formatTime(updatedAt),
        startedAt = startedAt?.let { formatTime(it) },
        finishedAt = finishedAt?.let { formatTime(it) },
        hasReport = resultSummary != null,
    )

    private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

    private fun readObject(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw).jsonObjectOrNull()
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun stringOf(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))
}

// ---------------------------------------------------------------------------
// Wire shapes
// ---------------------------------------------------------------------------

@Serializable
data class RunListing(
    val success: Boolean,
    val activeCount: Int = 0,
    val runs: List<RunSummary> = emptyList(),
    val note: String? = null,
) : ToolResultData

@Serializable
data class RunSummary(
    val runId: String,
    val agent: String,
    val status: String,
    val assignment: String,
    val lastActivity: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val hasReport: Boolean = false,
)

@Serializable
data class RunDetail(
    val success: Boolean,
    val runId: String,
    val agent: String = "",
    val status: String = "",
    val assignment: String = "",
    val startedAt: String? = null,
    val finishedAt: String? = null,
    /** The final answer, when one was recorded. */
    val finalReport: String? = null,
    /**
     * Whether a report was stored at all.
     *
     * SEPARATE FROM [finalReport] ON PURPOSE. A completed run that said nothing and a completed run
     * whose report was lost are different facts, and collapsing them makes the second invisible.
     */
    val resultSummaryRecorded: Boolean = false,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val timeline: List<EventRecord> = emptyList(),
    val timelineNote: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ToolCallRecord(
    val invocationId: String,
    val tool: String,
    val arguments: String,
    /**
     * Whether the tool's actual output was recorded.
     *
     * FALSE MEANS NOBODY OBSERVED IT. The call may have run and its result may have been lost, or it
     * may never have finished. Neither is "returned nothing", which is why this is its own field
     * rather than an empty [output].
     */
    val outputRecorded: Boolean,
    val output: String? = null,
    val outputSucceeded: Boolean? = null,
    val outputDurationMs: Long? = null,
    val at: String,
)

@Serializable
data class MessageRecord(
    val at: String,
    val author: String,
    val type: String,
    val content: String,
    val delivery: String,
)

@Serializable
data class EventRecord(
    val sequence: Long,
    val type: String,
    val at: String,
    val summary: String,
)

@Serializable
data class MessageSent(
    val success: Boolean,
    val runId: String,
    val runStatus: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class CancelOutcome(
    val success: Boolean,
    val runId: String,
    val fromStatus: String? = null,
    val stoppedLiveExecution: Boolean = false,
    val alreadyTerminal: Boolean = false,
    val sentence: String,
) : ToolResultData

@Serializable
data class CancelAllOutcome(
    val success: Boolean,
    val stopped: Int = 0,
    val alreadyFinished: Int = 0,
    val sentences: List<String> = emptyList(),
    val sentence: String,
) : ToolResultData
