// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.data.tools.registry.ToolProgressEvent
import com.unuslumen.app.data.tools.registry.ToolTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns the dispatcher's decision into a ledger event, once, for one run.
 *
 * PER RUN RATHER THAN PER APP. A telemetry sink that did not know its run would have to be
 * told on every call which run to write to, and the first call that forgot would attach a
 * decision to the wrong execution. This is constructed for a run and carries that run's id,
 * so there is nothing to forget.
 *
 * WRITES ARE FIRE AND FORGET. A permission decision is evidence recorded for later reading;
 * it must never delay the tool call or fail it. A launch on an application-lifetime scope
 * means the write happens whether or not the caller is still around, and a failure is logged
 * rather than thrown, because losing a telemetry row must not become losing the work.
 */
class AgentRunToolTelemetry(
    private val runId: String,
    private val ledger: AgentRunLedger,
    private val scope: CoroutineScope,
) : ToolTelemetry {

    override fun permissionGranted(
        toolName: String,
        resolvedName: String,
        permissions: List<String>,
        runId: String?,
        invocationId: String?,
    ) {
        // THE RUN IS THIS OBJECT'S, NOT THE PARAMETER'S. If a caller passes a different id,
        // that is a caller that constructed the wrong sink, and writing under the parameter
        // would put one run's decision in another run's history.
        val owning = runId ?: this.runId
        if (owning != this.runId) {
            android.util.Log.w(
                "guru_agents",
                "Tool telemetry for '$owning' arrived at the sink for '${this.runId}'; " +
                    "the decision is recorded under its own run.",
            )
        }
        scope.launch {
            runCatching {
                ledger.append(
                    owning,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_PERMISSION_GRANTED,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_RUNTIME,
                        payload = buildPayload(
                            "tool" to resolvedName,
                            "requested" to toolName,
                            "permissions" to permissions.joinToString(","),
                            "permissionCount" to permissions.size.toString(),
                            "invocationId" to (invocationId ?: ""),
                            "runtimeKind" to "AGENT_RUNTIME",
                        ),
                    )
                )
            }.onFailure {
                android.util.Log.w("guru_agents", "Permission telemetry write failed: ${it.message}")
            }
        }
    }

    override fun toolProgress(
        toolName: String,
        invocationId: String?,
        runId: String?,
        event: ToolProgressEvent,
    ) {
        recordProgress(toolName, invocationId, runId, event)
    }

    /**
     * The progress write, reachable without the interface's run parameter.
     *
     * THE RUNTIME HAS ALREADY DECIDED THE RUN when it builds this sink, so the call site
     * that constructs a [ToolProgressSink] lambda would otherwise have to pass a run id it
     * does not need to name. This keeps the interface honest and the call site short.
     */
    fun progressFor(toolName: String, invocationId: String?, event: ToolProgressEvent) =
        recordProgress(toolName, invocationId, null, event)

    private fun recordProgress(
        toolName: String,
        invocationId: String?,
        runId: String?,
        event: ToolProgressEvent,
    ) {
        val owning = runId ?: this.runId
        scope.launch {
            runCatching {
                ledger.append(
                    owning,
                    AgentRunLedger.EventDraft(
                        eventType = com.unuslumen.app.domain.agents.AgentEventTypes.TOOL_PROGRESS,
                        actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_TOOL,
                        payload = buildPayload(
                            "tool" to toolName,
                            "invocationId" to (invocationId ?: ""),
                            // THE TOOL'S OWN SENTENCE, BOUNDED. This is what the executor
                            // reported, not a summary of it, and it is capped because a tool
                            // that walks a network can produce a great many lines.
                            "message" to event.message.take(MAX_PROGRESS_CHARS),
                            "fraction" to (event.fraction?.toString() ?: ""),
                            "runtimeKind" to "AGENT_RUNTIME",
                        ),
                    )
                )
            }.onFailure {
                android.util.Log.w("guru_agents", "Tool progress write failed: ${it.message}")
            }
        }
    }

    /**
     * Build a flat JSON object.
     *
     * Hand-built for the same reason the runtime's own builder is: these payloads are flat
     * strings by construction, and pulling a serialization library in to write six keys
     * would be machinery for nothing.
     */
    private fun buildPayload(vararg pairs: Pair<String, String>): String = buildString {
        append('{')
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(quote(key)).append(':').append(quote(value))
        }
        append('}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
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
        append('"')
    }

    companion object {
        /** One progress line, bounded. A tool is free to report more; this keeps the row sane. */
        const val MAX_PROGRESS_CHARS = 500
    }
}
