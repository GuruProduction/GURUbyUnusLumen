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
 * The verbs GURU uses to see and govern the runs it has started.
 *
 * WHY THESE EXIST AS A SEPARATE SET FROM [AgentToolDefinitions]. That set is about agent
 * DEFINITIONS: what exists, what it is for, what tools it may hold, and handing one a job. This set
 * is about agent EXECUTIONS: which are running, what each is doing, what it produced, and stopping
 * one. Conflating the two is what produced `listAgents` being asked what was running and answering
 * with a catalogue.
 *
 * THE DESCRIPTIONS CARRY THE HONEST CONTRACT, because the model only knows what it is told. Every
 * one of these says what it will and will not report: a run whose tool call had no recorded output
 * is reported as having none rather than being described as though the call succeeded.
 */
object AgentRunToolDefinitions : ToolSetRegistration {

    const val LIST_AGENT_RUNS = "listAgentRuns"
    const val GET_AGENT_RUN = "getAgentRun"
    const val SEND_AGENT_MESSAGE = "sendAgentMessage"
    const val CANCEL_AGENT_RUN = "cancelAgentRun"
    const val CANCEL_ALL_AGENT_RUNS = "cancelAllAgentRuns"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_AGENT_RUNS,
            description = "Everything your agents are doing right now, and everything recent. " +
                "This is the live operational picture: one row per run with the agent's name, the " +
                "run id, whether it is working or waiting, the job it was given, and when it last " +
                "did anything. Call this when someone asks what your agents are up to, and call it " +
                "before assuming a run finished. If a run is WAITING_FOR_GURU or WAITING_FOR_USER " +
                "it is stuck until somebody acts, so say so rather than waiting for it. This reads " +
                "real run records, so it cannot be out of date.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "includeFinished",
                    type = ToolParameterType.Boolean,
                    required = false,
                    description = "True to include runs that already ended, newest first. Defaults " +
                        "to false, which lists only what is still live.",
                ),
                ToolParameter(
                    name = "agent",
                    type = ToolParameterType.String,
                    required = false,
                    description = "Limit to one agent by slug or name, when the question is about " +
                        "that agent rather than everything.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = GET_AGENT_RUN,
            description = "One run in full, by the run id that spawnAgent gave you. Returns, " +
                "separately and labelled: the run's state and final report, the ordered list of " +
                "what happened, the tool calls it made WITH the output each one actually " +
                "returned, and what was said between the agent and the person. A tool call whose " +
                "output was never recorded says so; that is not the same as a call that returned " +
                "nothing, and this tool never presents one as the other. Use it when someone asks " +
                "what an agent found or why it did something.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "runId",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The run id from spawnAgent, or from listAgentRuns.",
                ),
                ToolParameter(
                    name = "eventLimit",
                    type = ToolParameterType.Integer,
                    required = false,
                    description = "How many of the most recent history events to return. Defaults " +
                        "to 40. The tool-call outputs and the messages are always complete, " +
                        "because those are what a person actually reads.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SEND_AGENT_MESSAGE,
            description = "Say something to an agent that is running. It does not stop the work. " +
                "The message is picked up at the agent's next safe boundary, so it will not arrive " +
                "in the middle of a tool call. Use it to correct a misunderstanding, add something " +
                "the agent could not have known, change a priority, or ask it to report where it " +
                "has got to. It does not wait for an answer and the agent is not obliged to reply " +
                "immediately, so do not use it when you need something before you can continue; " +
                "that is a question the agent asks, not one you ask.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "runId",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The run to speak to, from listAgentRuns or spawnAgent.",
                ),
                ToolParameter(
                    name = "message",
                    type = ToolParameterType.String,
                    required = true,
                    description = "What to say. Complete sentences the agent can act on without " +
                        "asking you what you meant.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = CANCEL_AGENT_RUN,
            description = "Stop a run, whatever state it is in. This is the control for an agent " +
                "that is going wrong, has been stuck, is waiting on something nobody is going to " +
                "answer, or is simply no longer wanted. It stops the live execution AND finishes " +
                "the run record, so a run that had nothing driving it is cleared just as cleanly. " +
                "It is safe to repeat: cancelling an already-finished run changes nothing and says " +
                "so. A tool call that was in flight when you cancelled is reported afterwards as " +
                "having an unknown outcome; it is never claimed to have been undone.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "runId",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The run to stop, from listAgentRuns.",
                ),
                ToolParameter(
                    name = "reason",
                    type = ToolParameterType.String,
                    required = false,
                    description = "Why you are stopping it. Recorded on the run, so a person " +
                        "reading it later can tell why.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = CANCEL_ALL_AGENT_RUNS,
            description = "Stop every run that is still going. Every non-terminal run is stopped, " +
                "including ones that have nothing executing them, so this is the control for " +
                "\"stop everything\". It reports what it stopped and what was already finished. " +
                "Use it when someone asks you to stop all of them, or when the phone is under " +
                "pressure and nothing running is worth the cost.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "reason",
                    type = ToolParameterType.String,
                    required = false,
                    description = "Why everything is being stopped. Recorded on each run.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AgentRunToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
