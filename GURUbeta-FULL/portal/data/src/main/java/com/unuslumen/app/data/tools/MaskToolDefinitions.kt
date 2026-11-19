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
 * The mask tools (issue 491).
 *
 * WHAT A MASK IS, because the ticket asked and the answer shapes the tools. It is not
 * a stored record: it is a four-member enum rendered by the selector, with the
 * selection held in the portal screen's own state and never written anywhere. There is
 * no table, no row and no body text to author.
 *
 * So these tools READ the catalogue and say what it is. A `createMask` that wrote to
 * nothing would be the same defect the issue is about, which is a surface that reads as
 * capable while the system does nothing. When masks become stored data, the write verbs
 * go here beside these, against whatever the store turns out to be.
 *
 * WHAT THEY REPLACE. Of 607 tool names the app defined, zero contained "mask", while the
 * Self Improvement Engine's prompt tells the agent to inspect and follow the existing
 * mask architecture rather than inventing a parallel concept. It could not see the
 * architecture at all.
 */
object MaskToolDefinitions : ToolSetRegistration {

    const val LIST_MASKS = "listGuruMasks"
    const val GET_MASK = "getGuruMask"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_MASKS,
            description = "List the masks this GURU build carries, with what each one is for and which is the default. A mask is a role the portal works under, chosen by the owner from a selector above the chat bar; the catalogue is built into the app rather than stored, so this reports what exists rather than what has been authored. Read this BEFORE proposing anything mask-related, so you extend the real thing rather than inventing a parallel concept.",
            category = "masks",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = GET_MASK,
            description = "Read one mask in full, by the name listGuruMasks returned. Returns its purpose and whether the portal opens with it selected.",
            category = "masks",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "The mask's name, as listGuruMasks reports it"),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = MaskToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
