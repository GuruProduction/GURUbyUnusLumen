// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import ai.koog.agents.core.tools.ToolDescriptor
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Manages GURU-defined tools end to end.
 *
 * GURU-created tools ride TWO lists to the model on every request:
 *  - as full registry ToolDefinitions (Ollama-format wire paths: parameter
 *    schemas carried properly by ToolSchemaSerializer), and
 *  - as koog ToolDescriptors with real required/optional parameters
 *    (koog-brand cloud BYO executor paths).
 *
 * The merge points live in AiRepositoryImpl.sendMessage: neither list is
 * optional. GURU builds a tool, it is offered to its own model the same turn
 * and available the same session; no approval layer exists.
 */
@Factory
class GuruToolRegistryManager(
    private val guruToolRepository: GuruToolRepository,
    private val dynamicToolExecutor: DynamicToolExecutor
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * All approved GURU-created tools converted to genuine ToolDefinitions —
     * parameter schemas parsed and typed, name-collision-free with built-ins
     * (isNameAvailable guards that at define time).
     */
    suspend fun getGuruToolDefinitions(): List<ToolDefinition> {
        return guruToolRepository.getApprovedTools().mapNotNull { tool ->
            com.unuslumen.app.data.gurutools.GuruToolSchemaMapper.toolToDefinition(tool)
        }
    }

    /**
     * All approved GURU-created tools as full koog descriptors, real
     * required/optional parameters, for the koog cloud wire.
     */
    suspend fun getGuruToolDescriptors(): List<ToolDescriptor> {
        return guruToolRepository.getApprovedTools().mapNotNull { tool ->
            com.unuslumen.app.data.gurutools.GuruToolSchemaMapper.toolToDescriptor(tool)
        }
    }

    /**
     * Execute a GURU-defined tool by name. Returns the result as a JSON string.
     * Called from AiRepositoryImpl.executeToolCall when a tool call matches a
     * GURU-defined tool name.
     */
    suspend fun executeGuruTool(name: String, args: Map<String, Any?>): String {
        val tool = guruToolRepository.getToolByName(name)
            ?: throw IllegalArgumentException("GURU-defined tool not found: $name")

        if (tool.status != ToolStatus.APPROVED) {
            throw IllegalStateException("Tool '$name' is not approved for execution")
        }

        val result = dynamicToolExecutor.execute(tool, args)
        return result.toString()
    }

    /**
     * Check if a tool name belongs to an approved GURU-defined tool.
     */
    suspend fun isGuruTool(name: String): Boolean {
        val tool = guruToolRepository.getToolByName(name) ?: return false
        return tool.status == ToolStatus.APPROVED
    }

    /**
     * The merge for Ollama-wire paths: built-in definitions + GURU creations.
     */
    suspend fun refreshToolDefinitions(
        builtInDefinitions: List<ToolDefinition>
    ): List<ToolDefinition> {
        return builtInDefinitions + getGuruToolDefinitions()
    }

    /**
     * The merge for koog descriptor paths, with real parameters on every entry.
     */
    suspend fun refreshToolDescriptors(
        builtInDescriptors: List<ToolDescriptor>
    ): List<ToolDescriptor> {
        // Built-ins first, carrying their real parameter schemas (no bare
        // name+description guesses on the koog wire either).
        val builtinFull = builtInDescriptors.mapNotNull { original ->
            builtInDefinitionsForMerge().find { it.name == original.name }?.let { def ->
                GuruToolSchemaMapper.definitionToDescriptor(def)
            } ?: original
        }
        return builtinFull + getGuruToolDescriptors()
    }

    /** Definitions for the built-ins, fetched through the same registry the model wire reads. */
    private fun builtInDefinitionsForMerge(): List<ToolDefinition> = runCatching {
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.data.tools.registry.ToolRegistry>()
            .getAllDefinitions()
    }.getOrElse { emptyList() }

    /**
     * Refresh the raw merged descriptor list when the built-in definitions aren't
     * yet known to the caller (zero-arg form used at boot-time listing refresh).
     */
    suspend fun refreshMergedDefinitionList(): List<ToolDefinition> =
        refreshToolDefinitions(builtInDefinitionsForMerge())
}