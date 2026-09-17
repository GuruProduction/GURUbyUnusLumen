package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object TermuxToolDefinitions : ToolSetRegistration {
    const val TERMUX_INIT = "termuxInit"; const val TERMUX_EXEC = "termuxExec"; const val TERMUX_INSTALL = "termuxInstall"
    const val TERMUX_STATUS = "termuxStatus"; const val TERMUX_HAS_COMMAND = "termuxHasCommand"; const val TERMUX_DIAGNOSE = "termuxDiagnose"

    override val definitions = listOf(
        ToolDefinition(name = TERMUX_INIT, description = "Initialize the bundled Termux Linux environment AND the reverse engineering toolset.", category = "termux", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = TERMUX_EXEC, description = "Execute a shell command inside the bundled Termux Linux environment.", category = "termux", parameters = listOf(ToolParameter("command", ToolParameterType.ShellCommand, true, "Command to run"), ToolParameter("workingDir", ToolParameterType.String, false, "Working directory")), permissions = emptyList()),
        ToolDefinition(name = TERMUX_INSTALL, description = "Install packages inside the bundled Termux environment using apt.", category = "termux", parameters = listOf(ToolParameter("packages", ToolParameterType.String, true, "Space-separated packages")), permissions = emptyList()),
        ToolDefinition(name = TERMUX_STATUS, description = "Get the current status of the bundled Termux environment.", category = "termux", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = TERMUX_HAS_COMMAND, description = "Check if a specific command or binary exists in the Termux environment.", category = "termux", parameters = listOf(ToolParameter("command", ToolParameterType.String, true, "Command name")), permissions = emptyList()),
        ToolDefinition(name = TERMUX_DIAGNOSE, description = "Run a full diagnostic on the bundled Termux environment.", category = "termux", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = TermuxToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}