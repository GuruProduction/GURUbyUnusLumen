package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import kotlin.reflect.KClass

object UtilToolDefinitions : ToolSetRegistration {

    const val FORMAT_DATE = "formatDate"
    const val FORMAT_DATE_TOOL = "formatDate"  // Backwards compat alias for references in other tool sets

    override val definitions = listOf(
        ToolDefinition(
            name = FORMAT_DATE,
            description = "Convert a date in milliseconds to a formatted date string. Use to get a readable date from objects that contain date as milliseconds.",
            category = "util",
            parameters = listOf(
                ToolParameter("millis", ToolParameterType.Integer, required = true, description = "The date in milliseconds.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = UtilToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}