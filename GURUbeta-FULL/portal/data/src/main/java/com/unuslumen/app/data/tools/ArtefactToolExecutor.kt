// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentArtefactService
import com.unuslumen.app.data.agents.currentDispatchScope
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Registers an output against the run that produced it.
 *
 * THE RUN COMES FROM THE COROUTINE, NOT FROM AN ARGUMENT. An artefact names the run and the agent it
 * came from, so a model that could name a run could file its work under somebody else's history. The
 * dispatch scope already carries the conversation and the agent for exactly this reason, and this
 * reads the run from it.
 *
 * IT REFUSES RATHER THAN DEGRADING. No run is a refusal with the reason, an oversized inline body is
 * a refusal carrying both sizes, and a write that the service will not make is reported as not made.
 * A tool that answered "saved" for any of those would be the same defect as the room showing
 * ciphertext: something that looks like a record and is not one.
 */
@Factory
class ArtefactToolExecutor(
    private val artefacts: AgentArtefactService,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        if (toolName != ArtefactToolDefinitions.RECORD_OUTPUT) {
            return ToolExecutionResult.error("Unknown tool: $toolName")
        }

        val title = (args["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'title'. Give the output a name someone would look for it by."
            )
        }

        val runId = currentDispatchScope()?.conversationId()
            ?: return success(
                OutputRecorded(
                    recorded = false,
                    reason = "Nothing was registered: this call is not attached to a run, so there " +
                        "is no history to file the output under.",
                ),
                OutputRecorded.serializer(),
            )

        val kind = (args["kind"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "report"
        val filePath = (args["filePath"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val content = (args["content"] as? String)?.takeIf { it.isNotBlank() }
        val finalOutput = args["finalOutput"] as? Boolean ?: true

        if (filePath == null && content == null) {
            return success(
                OutputRecorded(
                    recorded = false,
                    reason = "Nothing was registered: give me either 'filePath' for something you " +
                        "wrote to the device, or 'content' for a short body.",
                ),
                OutputRecorded.serializer(),
            )
        }

        val storageKind = if (filePath != null) {
            com.unuslumen.app.database.entity.AgentArtefactEntity.STORAGE_FILE
        } else {
            com.unuslumen.app.database.entity.AgentArtefactEntity.STORAGE_INLINE
        }
        val storageRef = filePath ?: "run:$runId"

        val recorded = try {
            artefacts.recordArtefact(
                runId = runId,
                title = title,
                kind = kind,
                storageKind = storageKind,
                storageRef = storageRef,
                contentText = if (filePath == null) content else null,
                sizeBytes = content?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L,
                finalOutput = finalOutput,
            )
        } catch (e: AgentArtefactService.TooLargeForInline) {
            // THE SERVICE'S OWN SENTENCE, because it carries both numbers and the route out.
            return success(
                OutputRecorded(recorded = false, reason = "Nothing was registered: ${e.message}"),
                OutputRecorded.serializer(),
            )
        } catch (e: Exception) {
            return success(
                OutputRecorded(
                    recorded = false,
                    reason = "Nothing was registered: ${e.message ?: e.javaClass.simpleName}.",
                ),
                OutputRecorded.serializer(),
            )
        }

        return success(
            OutputRecorded(
                recorded = recorded != null,
                reason = if (recorded != null) {
                    "Registered as an output of this run."
                } else {
                    "Nothing was registered: no run with that id exists, which should not " +
                        "happen for a call made from inside one."
                },
                artefactId = recorded?.artefactId,
            ),
            OutputRecorded.serializer(),
        )
    }

    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))
}

/** What happened to the registration, in words the agent can act on. */
@Serializable
data class OutputRecorded(
    val recorded: Boolean,
    val reason: String,
    val artefactId: String? = null,
) : ToolResultData
