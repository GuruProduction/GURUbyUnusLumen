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

    override val definitions = listOf(
        ToolDefinition(
            name = GET_SYSTEM_PROMPT,
            description = "Get your current system prompt. This is the full assembled prompt fetched from the server. Use this to see what instructions and context you currently have.",
            category = "prompt",
            parameters = emptyList(),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = PromptToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}