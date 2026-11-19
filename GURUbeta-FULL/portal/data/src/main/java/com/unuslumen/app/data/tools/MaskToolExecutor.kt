// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.masks.GuruMasks
import kotlinx.serialization.json.Json

class MaskToolExecutor : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            MaskToolDefinitionName.LIST -> {
                val r = MaskListResult(
                    total = GuruMasks.ALL.size,
                    defaultMask = GuruMasks.default().name,
                    masks = GuruMasks.ALL.map { mask ->
                        MaskEntry(mask.name, mask.purpose, mask.isDefault)
                    },
                    note = "The mask catalogue is built into this build rather than stored, so it " +
                        "is the same list the portal's selector renders. A mask cannot be authored " +
                        "from here because there is nowhere for one to be stored; when masks become " +
                        "stored data the write verbs join these.",
                )
                ToolExecutionResult.success(r, json.encodeToString(MaskListResult.serializer(), r))
            }

            MaskToolDefinitionName.GET -> {
                val name = args["name"] as? String
                val mask = GuruMasks.byName(name)
                if (mask == null) {
                    val r = MaskReadResult(
                        success = false,
                        name = name.orEmpty(),
                        purpose = "",
                        error = "No mask called \"${name.orEmpty()}\" in this build. " +
                            "The masks that exist are: ${GuruMasks.names().joinToString(", ")}.",
                    )
                    return ToolExecutionResult.success(r, json.encodeToString(MaskReadResult.serializer(), r))
                }
                val r = MaskReadResult(
                    success = true,
                    name = mask.name,
                    purpose = mask.purpose,
                    isDefault = mask.isDefault,
                    error = null,
                )
                ToolExecutionResult.success(r, json.encodeToString(MaskReadResult.serializer(), r))
            }

            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    /** The definition names, kept beside the definitions they must match. */
    private object MaskToolDefinitionName {
        const val LIST = MaskToolDefinitions.LIST_MASKS
        const val GET = MaskToolDefinitions.GET_MASK
    }
}
