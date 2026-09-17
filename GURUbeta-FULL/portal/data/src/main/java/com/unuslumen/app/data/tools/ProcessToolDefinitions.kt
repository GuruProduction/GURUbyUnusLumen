package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ProcessToolDefinitions : ToolSetRegistration {

    const val LIST_PROCESSES = "listProcesses"
    const val KILL_PROCESS = "killProcess"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_PROCESSES,
            description = "List running processes on the device. Uses shell 'ps' command. Returns process IDs, names, and states.",
            category = "process",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = KILL_PROCESS,
            description = "Kill a process by PID. Use shell 'kill' command.",
            category = "process",
            parameters = listOf(
                ToolParameter("pid", ToolParameterType.String, required = true, description = "Process ID to kill"),
                ToolParameter("signal", ToolParameterType.Integer, required = false, description = "Signal to send, default SIGTERM (15). Use 9 for SIGKILL.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ProcessToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}