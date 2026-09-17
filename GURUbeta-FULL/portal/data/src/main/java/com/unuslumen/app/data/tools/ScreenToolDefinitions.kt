package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ScreenToolDefinitions : ToolSetRegistration {

    const val TAKE_SCREENSHOT = "takeScreenshot"
    const val RECORD_SCREEN = "recordScreen"

    override val definitions = listOf(
        ToolDefinition(
            name = TAKE_SCREENSHOT,
            description = "Take a screenshot of the current screen. Returns the file path of the captured image. Use when your human asks 'what's on my screen' or you need to see what they're looking at.",
            category = "screen",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = RECORD_SCREEN,
            description = "Start or stop screen recording. Use 'start' to begin recording, 'stop' to save the recording. Returns the file path when stopped.",
            category = "screen",
            parameters = listOf(
                ToolParameter("action", ToolParameterType.String, required = true, description = "'start' to begin recording, 'stop' to save and return the file"),
                ToolParameter("duration", ToolParameterType.Integer, required = false, description = "Duration in seconds for recording (only for 'start', default 30)")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ScreenToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}