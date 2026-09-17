package com.unuslumen.app.data.tools.registry

/**
 * ToolParameterType — Enum of all parameter types recognised by the tool system.
 *
 * Code, ShellCommand, and Script types tell ToolSecurityPolicy which parameters
 * contain executable code so it can inspect them for dangerous patterns.
 */
enum class ToolParameterType {
    String,
    Integer,
    Long,
    Boolean,
    Float,
    Enum,
    Code,
    ShellCommand,
    Script
}

/**
 * ToolParameter — A single parameter definition for a tool.
 *
 * Pure data. No koog dependency. No reflection.
 */
data class ToolParameter(
    val name: String,
    val type: ToolParameterType,
    val required: Boolean,
    val description: String,
    val enumValues: List<String>? = null
)