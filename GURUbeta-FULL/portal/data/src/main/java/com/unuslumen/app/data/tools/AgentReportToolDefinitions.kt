// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The verb an agent uses to say something mid-run without stopping.
 *
 * WHY THIS EXISTS BESIDE `askAssistant`. That verb pauses the run, which is right for a question the
 * agent cannot proceed without. But most of what an agent wants to say is not a question: a finding
 * that changes what the person would do, a blocker it has hit, a stage it has finished, a risk it
 * noticed, a request that somebody look at what it produced. Reporting any of those as a question
 * makes the agent stop for things it should simply mention, which is expensive for the person and
 * pointless for the work.
 *
 * IT NEVER PAUSES. The message is written to the run's conversation and the loop carries on. The
 * person finds it in the room, and the notification policy decides whether it is worth interrupting
 * them for, which is a decision made by the agent's own urgency settings rather than by the agent.
 *
 * IT DOES NOT WRITE THE MESSAGE ITSELF. The runtime owns the run id and the round, so the executor
 * validates and returns and the runtime records. An executor writing straight to the conversation
 * would need the run id through its arguments, which would let a model post into somebody else's run.
 */
object AgentReportToolDefinitions : ToolSetRegistration {

    const val REPORT_TO_PERSON = "reportToPerson"

    override val definitions = listOf(
        ToolDefinition(
            name = REPORT_TO_PERSON,
            description = "Tell the person something while you keep working. Use this for a finding " +
                "worth knowing, a blocker you have hit, a stage you have finished, a risk you " +
                "noticed, or a request that they look at what you produced. It does NOT stop your " +
                "work: the message is delivered to them and you carry on. If you genuinely cannot " +
                "continue without an answer, use askAssistant instead, because this one will not " +
                "wait for anybody. Choose the kind honestly: a finding is not a risk, and a " +
                "milestone is not a completion.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "kind",
                    type = ToolParameterType.String,
                    required = true,
                    description = "One of: " +
                        "clarification (stops the run and waits — prefer askAssistant for that), " +
                        "finding, blocker, milestone, risk, review."
                ),
                ToolParameter(
                    name = "message",
                    type = ToolParameterType.String,
                    required = true,
                    description = "What you want to say, in one or two plain sentences. The person " +
                        "reads this without your working context, so name the thing rather than " +
                        "referring to it."
                ),
                ToolParameter(
                    name = "urgency",
                    type = ToolParameterType.String,
                    required = false,
                    description = "low, normal or high. High means it should reach them even if an " +
                        "agent's quiet hours would normally hold it. Most messages are normal."
                ),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = AgentReportToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
