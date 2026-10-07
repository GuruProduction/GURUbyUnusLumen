// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * Serializable tool results for the module tool set. AutomationToolResults
 * pattern: every result implements ToolResultData so the dispatcher
 * handles it generically without knowing specific types.
 */

@Serializable
data class ModuleInfo(
    val id: String,
    val name: String,
    val displayName: String,
    val category: String,
    val status: String,
    val revision: Int,
    val hasDoor: Boolean
) : ToolResultData

@Serializable
data class CreateModuleResult(
    val id: String,
    val name: String,
    val displayName: String,
    val category: String,
    val status: String,
    val message: String = "Vessel created. Compose its room with saveModuleComposition."
) : ToolResultData

@Serializable
data class SaveCompositionResult(
    val id: String,
    val name: String,
    val revision: Int,
    val savedBytes: Int,
    val message: String = "Composition saved."
) : ToolResultData

@Serializable
data class SaveDataResult(
    val id: String,
    val savedBytes: Int,
    val message: String = "Module data space saved."
) : ToolResultData

@Serializable
data class GetModuleDataResult(
    val id: String,
    val dataJson: String,
    val found: Boolean = true
) : ToolResultData

@Serializable
data class ListModulesResult(
    val modules: List<ModuleInfo>,
    val total: Int,
    val active: Int
) : ToolResultData

@Serializable
data class GetModuleResult(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val category: String,
    val status: String,
    val revision: Int,
    val compositionHtml: String,
    val compositionCss: String,
    val compositionJs: String,
    val dataJson: String,
    val iconPath: String? = null,
    val found: Boolean = true
) : ToolResultData

@Serializable
data class RenameModuleResult(
    val id: String,
    val displayName: String,
    val message: String = "Renamed."
) : ToolResultData

@Serializable
data class RollbackModuleResult(
    val id: String,
    val rolledBackTo: Int,
    val newRevision: Int,
    val message: String = "Rolled back. The restore is itself saved as a new revision."
) : ToolResultData

@Serializable
data class ModuleRevisionsResult(
    val id: String,
    val revisions: List<RevisionInfo>
) : ToolResultData {
    @Serializable
    data class RevisionInfo(val revision: Int, val createdAt: Long, val sizeBytes: Int) : ToolResultData
}

@Serializable
data class RegisterModuleResult(
    val id: String,
    val displayName: String,
    val message: String = "Door registered. Your room now leads the lobby."
) : ToolResultData

@Serializable
data class RetireModuleResult(
    val id: String,
    val displayName: String,
    val message: String = "Door hidden. Everything is preserved and it can be registered again."
) : ToolResultData

@Serializable
data class DeleteModuleResult(
    val id: String,
    val displayName: String,
    val message: String = "Deleted. The vessel and its revisions are gone; automation history survives."
) : ToolResultData

@Serializable
data class ReorderTilesResult(
    val tiles: Int,
    val message: String = "Lobby order saved."
) : ToolResultData

@Serializable
data class SetModuleIconResult(
    val id: String,
    val iconPath: String?,
    val message: String = "Icon set."
) : ToolResultData