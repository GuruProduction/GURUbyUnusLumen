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
 * ObservationToolDefinitions — the action-and-observation log.
 *
 * Two verbs. logObservation records what a tool did to a screen; getObservations
 * reads the record back. Recording is normally done by the system after every
 * tool run, so the model only needs logObservation when it wants to attach a
 * fingerprint or tree the dispatcher could not see. Retrieval is the one the
 * model uses on a second attempt: it shows the exact tree and the exact call
 * that failed, which is what stops a brittle selector being re-guessed.
 */
object ObservationToolDefinitions : ToolSetRegistration {

    const val LOG_OBSERVATION = "logObservation"
    const val GET_OBSERVATIONS = "getObservations"

    override val definitions = listOf(
        ToolDefinition(
            name = LOG_OBSERVATION,
            description = "Record one tool run against an app screen: the package, the tool, the exact arguments, a result summary, whether it succeeded, and optionally the screen fingerprint and accessibility tree. The system records every tool run automatically; call this only to attach a fingerprint or tree that the automatic write could not capture. Writing an observation never changes the app state it describes.",
            category = "observation",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, required = true, description = "The app package the tool was aimed at, e.g. 'com.android.settings'"),
                ToolParameter("toolName", ToolParameterType.String, required = true, description = "The tool that ran, e.g. 'clickText'"),
                ToolParameter("argumentsJson", ToolParameterType.String, required = true, description = "The exact arguments the call carried, as a JSON object string"),
                ToolParameter("resultSummary", ToolParameterType.String, required = false, description = "What happened: the error message on failure, or a short note on the result on success"),
                ToolParameter("success", ToolParameterType.Boolean, required = false, description = "Whether the tool succeeded. Default false, because an observation is usually written to explain a failure"),
                ToolParameter("screenFingerprint", ToolParameterType.String, required = false, description = "Optional JSON array of the visible resource ids, sorted, with the targeted node excluded. Omit when no window could be read"),
                ToolParameter("accessibilityTreeJson", ToolParameterType.String, required = false, description = "Optional compact serialised accessibility tree captured at the moment of the call. Omit when no window could be read"),
                ToolParameter("activityClass", ToolParameterType.String, required = false, description = "Optional root node class name of the window that was acted on"),
                ToolParameter("conversationId", ToolParameterType.String, required = false, description = "Optional conversation id this call belonged to"),
                ToolParameter("durationMs", ToolParameterType.Long, required = false, description = "How long the tool took, in milliseconds. Default 0 when unknown")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GET_OBSERVATIONS,
            description = "Read past tool observations for an app, a tool, or both, newest first, with the stored accessibility tree and screen fingerprint intact. Use this before retrying a tool that failed here before: it shows the exact tree and the exact arguments of the failing call, so the next attempt can compare the current screen against the recorded one instead of guessing a selector again.",
            category = "observation",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, required = false, description = "Only return observations for this app package, e.g. 'com.android.settings'"),
                ToolParameter("toolName", ToolParameterType.String, required = false, description = "Only return observations of this tool, e.g. 'clickText'. Combining both filters returns the calls that tool made against that app"),
                ToolParameter("limit", ToolParameterType.Integer, required = false, description = "Maximum number of observations to return, default 10"),
                ToolParameter("failuresOnly", ToolParameterType.Boolean, required = false, description = "When a package name is given, return only that app's failed observations. Default false")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ObservationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = ObservationToolResultExtractor::class
}
