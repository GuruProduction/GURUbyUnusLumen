package com.unuslumen.app.data.tools.registry

/**
 * ToolRegistry — Dynamic tool registry.
 *
 * Three HashMaps keyed by tool name for O(1) lookup.
 * A parameter-to-tool index for reverse lookup used by ToolNameResolver.
 * No hardcoded references to any specific tool set.
 *
 * New tools are registered at startup via ToolRegistration which uses
 * KSP-generated discovery. Dynamic tools from GuruToolRegistryManager
 * register through the same mechanism.
 */
class ToolRegistry {
    private val definitions = HashMap<String, ToolDefinition>()
    private val executors = HashMap<String, ToolExecutor>()
    private val extractors = HashMap<String, ToolResultExtractor?>()
    private val paramIndex = HashMap<String, MutableList<String>>()

    fun register(
        definition: ToolDefinition,
        executor: ToolExecutor,
        extractor: ToolResultExtractor?
    ) {
        definitions[definition.name] = definition
        executors[definition.name] = executor
        extractors[definition.name] = extractor
        for (param in definition.parameters) {
            paramIndex.getOrPut(param.name.lowercase()) { mutableListOf() }.add(definition.name)
        }
    }

    fun getDefinition(name: String): ToolDefinition? = definitions[name]

    fun getExecutor(name: String): ToolExecutor? = executors[name]

    fun getExtractor(name: String): ToolResultExtractor? = extractors[name]

    fun hasTool(name: String): Boolean = definitions.containsKey(name)

    fun getAllDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun getAllToolNames(): Set<String> = definitions.keys

    fun getParameterIndex(): Map<String, List<String>> = paramIndex.mapValues { it.value.toList() }
}