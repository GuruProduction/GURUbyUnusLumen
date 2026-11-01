// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * ToolExecutor — Interface for tool execution.
 *
 * Every tool set's executor implements this. The dispatcher calls execute()
 * with the resolved tool name and parsed arguments. The executor runs the
 * actual Kotlin code that touches the device/OS and returns a result.
 *
 * The executor stays as Kotlin forever because it interfaces with Android.
 */
interface ToolExecutor {

    suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult

    /**
     * Run a tool and report what it is finding while it runs.
     *
     * THIS IS THE STREAMING CHANNEL, and it defaults to plain [execute] on
     * purpose. Most tools finish in well under a second and have nothing
     * useful to say mid-flight, so they inherit the old behaviour by not
     * overriding this at all. The ones that do take real time — a network
     * walk, a long scan, a bulk import — override it and call [progress] as
     * they go, and the dispatcher carries those reports onto the conversation
     * flow so they reach the canvas live.
     *
     * The alternative was to add a sink parameter to [execute] itself, which
     * would have meant editing seventy-eight executors to thread through an
     * argument that seventy-seven of them ignore. A defaulted method gives the
     * same capability with no churn and no chance of a silent miss.
     */
    suspend fun executeWithProgress(
        toolName: String,
        args: Map<String, Any?>,
        progress: ToolProgressSink,
    ): ToolExecutionResult = execute(toolName, args)
}
