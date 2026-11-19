// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.prompt.PromptSections
import com.unuslumen.app.domain.repository.PromptRepository

/**
 * The prompt-half executor (issue 492).
 *
 * ONE READ BECAME THREE. `getSystemPrompt` returns the whole assembled string, which a
 * model cannot navigate: it can see that an instruction is wrong and cannot find the
 * section that says it, so a proposal would have to quote by hand and might quote the
 * wrong paragraph.
 *
 * THE THIRD VERB PROPOSES AND DOES NOT APPLY, and it says so in its own reply. Prompt
 * rows live in the server's database and this device holds no credential to write
 * them. A tool that reported "applied" for a change that never left the phone would be
 * exactly the defect this whole issue is about: a surface that reads as configured
 * while the system does nothing.
 */
class PromptToolExecutor(
    private val promptRepository: PromptRepository
) : ToolExecutor {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            PromptToolDefinitions.GET_SYSTEM_PROMPT -> {
                val prompt = promptRepository.getSystemPrompt()
                val rawJson = json.encodeToString(kotlinx.serialization.serializer<String>(), prompt)
                ToolExecutionResult(
                    rawJson = rawJson,
                    resultData = null,
                    resultObject = null,
                    success = true,
                    error = null
                )
            }

            PromptToolDefinitions.LIST_PROMPT_SECTIONS -> {
                val sections = PromptSections.split(promptRepository.getSystemPrompt())
                val r = PromptSectionListResult(
                    total = sections.size,
                    sections = sections.map {
                        PromptSectionEntry(it.id, it.heading, it.startOffset, it.length)
                    },
                )
                ToolExecutionResult.success(r, json.encodeToString(PromptSectionListResult.serializer(), r))
            }

            PromptToolDefinitions.READ_PROMPT_SECTION -> {
                val sectionId = (args["sectionId"] as? String)?.trim().orEmpty()
                if (sectionId.isEmpty()) {
                    val r = PromptSectionReadResult(false, "", "", error = "Missing 'sectionId'")
                    return ToolExecutionResult.success(r, json.encodeToString(PromptSectionReadResult.serializer(), r))
                }
                val text = PromptSections.read(promptRepository.getSystemPrompt(), sectionId)
                val r = if (text == null) {
                    PromptSectionReadResult(
                        success = false,
                        sectionId = sectionId,
                        text = "",
                        error = "No section called \"$sectionId\" in the current prompt. " +
                            "Call listPromptSections to see the ids this build actually has.",
                    )
                } else {
                    PromptSectionReadResult(success = true, sectionId = sectionId, text = text, error = null)
                }
                ToolExecutionResult.success(r, json.encodeToString(PromptSectionReadResult.serializer(), r))
            }

            PromptToolDefinitions.PROPOSE_PROMPT_EDIT -> {
                val sectionId = (args["sectionId"] as? String)?.trim().orEmpty()
                val proposed = (args["proposedText"] as? String).orEmpty()
                val rationale = (args["rationale"] as? String)?.trim().orEmpty()

                val refusal = when {
                    sectionId.isEmpty() -> "Missing 'sectionId'"
                    proposed.isBlank() -> "Missing 'proposedText': name the exact text the section should say"
                    rationale.isEmpty() -> "Missing 'rationale': say why the current text is wrong or insufficient"
                    else -> null
                }
                if (refusal != null) {
                    val r = PromptEditProposalResult(false, sectionId, "", refusal)
                    return ToolExecutionResult.success(r, json.encodeToString(PromptEditProposalResult.serializer(), r))
                }

                // A proposal against a section that does not exist is a proposal nobody
                // can apply, so it is refused with the real ids rather than recorded.
                val current = PromptSections.read(promptRepository.getSystemPrompt(), sectionId)
                if (current == null) {
                    val r = PromptEditProposalResult(
                        success = false,
                        sectionId = sectionId,
                        currentText = "",
                        error = "No section called \"$sectionId\". Call listPromptSections for the real ids.",
                    )
                    return ToolExecutionResult.success(r, json.encodeToString(PromptEditProposalResult.serializer(), r))
                }

                // The proposal is recorded with the text it would replace, so a human
                // reading it does not have to reconstruct what changed.
                val record = PromptEditProposalResult(
                    success = true,
                    sectionId = sectionId,
                    currentText = current,
                    error = null,
                    proposedText = proposed,
                    rationale = rationale,
                    applied = false,
                    note = "Recorded, not applied. Prompt rows are served from the server database and this " +
                        "device has no write credential for them. Your human can apply this in the panel.",
                )
                ToolExecutionResult.success(
                    record,
                    json.encodeToString(PromptEditProposalResult.serializer(), record),
                )
            }

            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}
