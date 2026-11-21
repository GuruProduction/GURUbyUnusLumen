// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Validates a report and hands it back as a value for the runtime to record.
 *
 * THE SAME DIVISION AS `recordDecision`, AND FOR THE SAME REASON. The ledger and the conversation
 * are written by the runtime, because the runtime is what knows which run and which round the call
 * belongs to. An executor that wrote the message itself would need the run id as an argument, and a
 * model that can name a run is a model that can post into a different one.
 *
 * THREE REFUSALS, EACH A SENTENCE THE MODEL CAN ACT ON. A missing message, an unknown kind, and a
 * clarification asked through the wrong verb. That last one is a real distinction rather than
 * pedantry: this verb does not pause anything, so an agent that asks a question here would carry on
 * without the answer it said it needed.
 */
@Factory
class AgentReportToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        if (toolName != AgentReportToolDefinitions.REPORT_TO_PERSON) {
            return ToolExecutionResult.error("Unknown tool: $toolName")
        }

        val message = (args["message"] as? String)?.trim().orEmpty()
        if (message.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'message'. Say what you want the person to know, in plain sentences: they " +
                    "read this without your working context."
            )
        }

        val requested = (args["kind"] as? String)?.trim().orEmpty()
        if (requested.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'kind'. Use one of: " +
                    com.unuslumen.app.domain.agents.AgentInitiatedMessages.values().joinToString(", ") +
                    ". The kind is what tells them whether this needs an answer."
            )
        }

        val option = com.unuslumen.app.domain.agents.AgentInitiatedMessages.option(requested)
            ?: return ToolExecutionResult.error(
                "'$requested' is not a kind of message. Use one of: " +
                    com.unuslumen.app.domain.agents.AgentInitiatedMessages.values().joinToString(", ") + "."
            )

        /*
         * A QUESTION ASKED THROUGH THE VERB THAT DOES NOT WAIT IS A DEFECT IN THE CALL, not a
         * preference. The agent has said it cannot continue without knowing something and then picked
         * the tool that carries on regardless, so the answer would arrive after the work it was needed
         * for. Redirecting it costs one round; letting it through costs the job.
         */
        if (option.pauses) {
            return ToolExecutionResult.error(
                "A clarification needs an answer, so call askAssistant instead: it stops the run and " +
                    "waits. This verb deliberately carries on without waiting, which is wrong for a " +
                    "question you said you cannot continue without."
            )
        }

        val urgency = (args["urgency"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val recorded = ReportedMessage(
            kind = option.type,
            message = message,
            urgency = when (urgency) {
                "low" -> "LOW"
                "high" -> "HIGH"
                else -> "NORMAL"
            },
        )

        return ToolExecutionResult.success(
            recorded,
            json.encodeToString(ReportedMessage.serializer(), recorded),
        )
    }
}

/**
 * One report, as the agent put it.
 *
 * [kind] IS THE STORED TYPE rather than the word the model used, so the conversation and any surface
 * reading it agree with the vocabulary in one place.
 */
@Serializable
data class ReportedMessage(
    val kind: String,
    val message: String,
    val urgency: String,
) : com.unuslumen.app.data.tools.registry.ToolResultData
