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

object PromptToolDefinitions : ToolSetRegistration {

    const val GET_SYSTEM_PROMPT = "getSystemPrompt"

    // THE PROMPT-HALF VERBS (issue 492).
    //
    // The registry had exactly one name containing "prompt", which returned the whole
    // assembled string. Section 8 of the Self Improvement Engine's remit requires a
    // record of meaningful changes to the agent's own instructions, against a surface
    // it could not read in parts.
    //
    // THE WRITE IS A PROPOSAL, AND THAT IS THE HONEST SHAPE. Prompt rows live in the
    // server's database, and the device holds no credential to write them; a tool
    // claiming otherwise would be the same class of defect as the prompt rows that
    // never reached a device at all. So a change is recorded as a proposal the owner
    // can act on, and the tool says so in the reply rather than implying it applied.
    const val LIST_PROMPT_SECTIONS = "listPromptSections"
    const val READ_PROMPT_SECTION = "readPromptSection"
    const val PROPOSE_PROMPT_EDIT = "proposePromptEdit"

    override val definitions = listOf(
        ToolDefinition(
            name = GET_SYSTEM_PROMPT,
            description = "Get your current system prompt. This is the full assembled prompt fetched from the server. Use this to see what instructions and context you currently have.",
            category = "prompt",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_PROMPT_SECTIONS,
            description = "List the sections your current system prompt is assembled from, with each one's heading, offset and length, in the order the server joins them. The assembled prompt is one string with no row delimiters, so these are the readable pieces recoverable from it: one per blank-line-separated block, and never split inside a code fence. Use this before proposing an edit, so you name the section you actually mean.",
            category = "prompt",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = READ_PROMPT_SECTION,
            description = "Read one section of your current system prompt in full, by the id that listPromptSections returned (such as section-3).",
            category = "prompt",
            parameters = listOf(
                ToolParameter("sectionId", ToolParameterType.String, required = true, description = "The section id from listPromptSections, such as section-3"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = PROPOSE_PROMPT_EDIT,
            description = "Propose a change to one of your own prompt sections. This RECORDS the proposal and reports it to your human; it does not apply it, because prompt rows live on the server and this device has no write credential for them. Use it when you believe an instruction is wrong or missing, with the exact replacement text so the change can be applied without guesswork.",
            category = "prompt",
            parameters = listOf(
                ToolParameter("sectionId", ToolParameterType.String, required = true, description = "The section id from listPromptSections"),
                ToolParameter("proposedText", ToolParameterType.String, required = true, description = "The exact text the section should say instead"),
                ToolParameter("rationale", ToolParameterType.String, required = true, description = "Why the current text is wrong or insufficient"),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = PromptToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}