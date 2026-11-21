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
 * The verb an agent uses to register something it produced.
 *
 * WHY THIS HAD TO EXIST. `AgentArtefactService` could record an artefact, and nothing an agent could
 * call ever did. So the outputs tab was always empty, not because agents produce nothing but because
 * a report written into a chat message is not an artefact: it has no identity, no provenance and
 * nothing another run could point at. The owner's report was "nothing in outputs", and that was
 * literally true for every run ever made.
 *
 * A REPORT IN THE CHAT IS NOT AN OUTPUT. This is what turns one into a thing with a title, a kind and
 * a provenance trail back to the run that made it, so the room can list it and a later run can be
 * told about it.
 *
 * IT POINTS AT CONTENT RATHER THAN SWALLOWING IT. A file on the device, a project document or a media
 * item is named by its store and reference. Only a small body is carried inline, and an oversized one
 * is refused with its actual size rather than truncated into something that reads as complete.
 */
object ArtefactToolDefinitions : ToolSetRegistration {

    const val RECORD_OUTPUT = "recordOutput"

    override val definitions = listOf(
        ToolDefinition(
            name = RECORD_OUTPUT,
            description = "Register something you produced, so it appears in this run's outputs " +
                "rather than only in your report. Use it for a document, a report, a file you " +
                "wrote, code, a data set or an image. Give it a title someone can recognise and say " +
                "where the content is: a file path if you wrote one, or the body itself if it is " +
                "short. A finding left in a chat message has no identity and cannot be found again; " +
                "this gives it one.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "title",
                    type = ToolParameterType.String,
                    required = true,
                    description = "What it is, in the words a person would use to look for it. " +
                        "'Kettle probe firmware notes' rather than 'output'.",
                ),
                ToolParameter(
                    name = "kind",
                    type = ToolParameterType.String,
                    required = false,
                    description = "report, document, code, data, image or other. Defaults to report.",
                ),
                ToolParameter(
                    name = "filePath",
                    type = ToolParameterType.String,
                    required = false,
                    description = "The absolute path, when you wrote the output to the device. " +
                        "Preferred over the body itself for anything long.",
                ),
                ToolParameter(
                    name = "content",
                    type = ToolParameterType.String,
                    required = false,
                    description = "The body itself, when it is short and there is no file. Large " +
                        "bodies are refused rather than truncated, so write those to a file and " +
                        "pass filePath instead.",
                ),
                ToolParameter(
                    name = "finalOutput",
                    type = ToolParameterType.Boolean,
                    required = false,
                    description = "True when this is the finished deliverable, false for work on the " +
                        "way. Defaults to true.",
                ),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ArtefactToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
