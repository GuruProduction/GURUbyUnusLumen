// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.CreateModuleRequest
import com.unuslumen.app.domain.model.GuruModule
import com.unuslumen.app.domain.model.TileOrder
import com.unuslumen.app.domain.repository.ModuleRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes the module tool verbs against the real module repository.
 * AutomationToolExecutor pattern: when-dispatch on tool name, typed results,
 * JSON payloads back to the numen.
 */
class ModuleToolExecutor(private val moduleRepository: ModuleRepository) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    /** Module IDs are UUIDs; names arrive as kebab or snake slugs. */
    private suspend fun resolveModule(idOrName: String): GuruModule? =
        moduleRepository.getModule(idOrName) ?: moduleRepository.getModuleByName(idOrName)

    /** Returns null with the error already written to the caller's result channel via [writeError]. */
    private suspend fun requireModuleOrNull(idOrName: String): GuruModule? = resolveModule(idOrName)

    private fun sanitizeName(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9_]"), "_")
        .replace(Regex("^_+|_+$"), "")

    private fun sanitizeCategory(category: String): String =
        if (category.isBlank()) "Home" else category.trim().replaceFirstChar { it.uppercase() }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ModuleToolDefinitions.CREATE_MODULE -> createModule(args)
        ModuleToolDefinitions.LIST_MODULES -> listModules()
        ModuleToolDefinitions.GET_MODULE -> getModule(args)
        ModuleToolDefinitions.RENAME_MODULE -> renameModule(args)
        ModuleToolDefinitions.SAVE_MODULE_COMPOSITION -> saveComposition(args)
        ModuleToolDefinitions.SAVE_MODULE_DATA -> saveData(args)
        ModuleToolDefinitions.GET_MODULE_DATA -> getData(args)
        ModuleToolDefinitions.GET_MODULE_REVISIONS -> getRevisions(args)
        ModuleToolDefinitions.ROLLBACK_MODULE -> rollbackModule(args)
        ModuleToolDefinitions.REGISTER_MODULE -> registerModule(args)
        ModuleToolDefinitions.RETIRE_MODULE -> retireModule(args)
        ModuleToolDefinitions.DELETE_MODULE -> deleteModule(args)
        ModuleToolDefinitions.SET_MODULE_ICON -> setIcon(args)
        ModuleToolDefinitions.REORDER_TILES -> reorderTiles(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createModule(args: Map<String, Any?>): ToolExecutionResult {
        val rawName = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val rawCategory = args["category"] as? String ?: return ToolExecutionResult.error("Missing 'category'")

        val name = sanitizeName(rawName)
        if (name.isBlank()) return ToolExecutionResult.error("Name cannot be empty")
        if (!name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            return ToolExecutionResult.error("Name must start with a lowercase letter and use only lowercase letters, numbers and underscores")
        }
        if (!moduleRepository.isNameAvailable(name)) {
            return ToolExecutionResult.error("A module named '$name' already exists")
        }

        val module = moduleRepository.createModule(
            CreateModuleRequest(
                name = name,
                displayName = displayName.trim(),
                description = description.trim(),
                category = sanitizeCategory(rawCategory)
            )
        )
        val r = CreateModuleResult(module.id, module.name, module.displayName, module.category, module.status.name)
        return ToolExecutionResult.success(r, json.encodeToString(CreateModuleResult.serializer(), r))
    }

    private suspend fun listModules(): ToolExecutionResult {
        val modules = moduleRepository.getAllModules()
        val infos = modules.map {
            ModuleInfo(it.id, it.name, it.displayName, it.category, it.status.name, it.revision, it.status.name == "ACTIVE")
        }
        val active = modules.count { it.status.name == "ACTIVE" }
        val r = ListModulesResult(infos, modules.size, active)
        return ToolExecutionResult.success(r, json.encodeToString(ListModulesResult.serializer(), r))
    }

    private suspend fun getModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val r = GetModuleResult(
            module.id, module.name, module.displayName, module.description, module.category,
            module.status.name, module.revision, module.compositionHtml, module.compositionCss,
            module.compositionJs, module.dataJson, module.iconPath
        )
        return ToolExecutionResult.success(r, json.encodeToString(GetModuleResult.serializer(), r))
    }

    private suspend fun renameModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val updated = moduleRepository.renameModule(module.id, displayName.trim())
        val r = RenameModuleResult(updated.id, updated.displayName)
        return ToolExecutionResult.success(r, json.encodeToString(RenameModuleResult.serializer(), r))
    }

    private suspend fun saveComposition(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val html = args["html"] as? String ?: return ToolExecutionResult.error("Missing 'html'")
        val css = args["css"] as? String ?: ""
        val js = args["js"] as? String ?: ""
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        if (html.isBlank()) return ToolExecutionResult.error("Composition HTML cannot be empty")
        val updated = moduleRepository.saveComposition(module.id, html, css, js)
        val r = SaveCompositionResult(updated.id, updated.name, updated.revision, html.length)
        return ToolExecutionResult.success(r, json.encodeToString(SaveCompositionResult.serializer(), r))
    }

    private suspend fun saveData(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val dataJson = args["dataJson"] as? String ?: return ToolExecutionResult.error("Missing 'dataJson'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        // A room's own data space must be valid JSON — garbage in the store
        // would rot every room that reads it back.
        try { json.parseToJsonElement(dataJson) } catch (e: Exception) {
            return ToolExecutionResult.error("dataJson is not valid JSON: ${e.message}")
        }
        moduleRepository.saveData(module.id, dataJson)
        val r = SaveDataResult(module.id, dataJson.length)
        return ToolExecutionResult.success(r, json.encodeToString(SaveDataResult.serializer(), r))
    }

    private suspend fun getData(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val data = moduleRepository.getData(module.id) ?: "{}"
        val r = GetModuleDataResult(module.id, data)
        return ToolExecutionResult.success(r, json.encodeToString(GetModuleDataResult.serializer(), r))
    }

    private suspend fun getRevisions(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val revisions = moduleRepository.getRevisions(module.id).map {
            ModuleRevisionsResult.RevisionInfo(it.revision, it.createdAt, it.compositionHtml.length + it.compositionCss.length + it.compositionJs.length)
        }
        val r = ModuleRevisionsResult(module.id, revisions)
        return ToolExecutionResult.success(r, json.encodeToString(ModuleRevisionsResult.serializer(), r))
    }

    private suspend fun rollbackModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val revisionStr = args["revision"] as? String ?: return ToolExecutionResult.error("Missing 'revision'")
        val revision = revisionStr.toIntOrNull() ?: return ToolExecutionResult.error("Revision must be a number")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val updated = moduleRepository.rollbackToRevision(module.id, revision)
        val r = RollbackModuleResult(updated.id, revision, updated.revision)
        return ToolExecutionResult.success(r, json.encodeToString(RollbackModuleResult.serializer(), r))
    }

    private suspend fun registerModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        if (module.compositionHtml.isBlank()) {
            return ToolExecutionResult.error("The room has no composition yet. Save a composition before registering the door.")
        }
        moduleRepository.registerModule(module.id)
        val r = RegisterModuleResult(module.id, module.displayName)
        return ToolExecutionResult.success(r, json.encodeToString(RegisterModuleResult.serializer(), r))
    }

    private suspend fun retireModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        moduleRepository.retireModule(module.id)
        val r = RetireModuleResult(module.id, module.displayName)
        return ToolExecutionResult.success(r, json.encodeToString(RetireModuleResult.serializer(), r))
    }

    private suspend fun deleteModule(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val confirmed = args["confirmed"] as? String ?: return ToolExecutionResult.error("Missing 'confirmed'")
        if (confirmed.trim().uppercase() != "DELETE") {
            return ToolExecutionResult.error("Deletion requires the human's explicit confirmation. Ask them to confirm by name first, then pass confirmed='DELETE'.")
        }
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        moduleRepository.deleteModule(module.id)
        val r = DeleteModuleResult(module.id, module.displayName)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteModuleResult.serializer(), r))
    }

    private suspend fun setIcon(args: Map<String, Any?>): ToolExecutionResult {
        val idOrName = args["moduleId"] as? String ?: return ToolExecutionResult.error("Missing 'moduleId'")
        val iconPath = args["iconPath"] as? String ?: return ToolExecutionResult.error("Missing 'iconPath'")
        val module = requireModuleOrNull(idOrName) ?: return ToolExecutionResult.error("Module not found: $idOrName")
        val updated = moduleRepository.setIcon(module.id, iconPath)
        val r = SetModuleIconResult(updated.id, updated.iconPath)
        return ToolExecutionResult.success(r, json.encodeToString(SetModuleIconResult.serializer(), r))
    }

    private suspend fun reorderTiles(args: Map<String, Any?>): ToolExecutionResult {
        val raw = args["orderedTileIds"] as? String ?: return ToolExecutionResult.error("Missing 'orderedTileIds'")
        val tiles: List<String> = try {
            json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            return ToolExecutionResult.error("orderedTileIds must be a JSON array of tile keys: ${e.message}")
        }
        if (tiles.isEmpty()) return ToolExecutionResult.error("orderedTileIds cannot be empty — a full order keeps every tile")
        moduleRepository.saveTileOrders(tiles.mapIndexed { index, id -> TileOrder(id = id, sortOrder = index) })
        val r = ReorderTilesResult(tiles.size)
        return ToolExecutionResult.success(r, json.encodeToString(ReorderTilesResult.serializer(), r))
    }
}