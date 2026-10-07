// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * Module tool set — the numen's hands for building rooms.
 *
 * The verbs the module-forge doctrine teaches: instantiate the vessel,
 * compose, persist, register the door when the human approves, tend the
 * room over time, roll back any bad composition. These are real tools,
 * executed against real stores, registered in the same self-registration
 * pattern as every other set.
 */
object ModuleToolDefinitions : ToolSetRegistration {
    const val CREATE_MODULE = "createModule"
    const val LIST_MODULES = "listModules"
    const val GET_MODULE = "getModule"
    const val RENAME_MODULE = "renameModule"
    const val SAVE_MODULE_COMPOSITION = "saveModuleComposition"
    const val SAVE_MODULE_DATA = "saveModuleData"
    const val GET_MODULE_DATA = "getModuleData"
    const val GET_MODULE_REVISIONS = "getModuleRevisions"
    const val ROLLBACK_MODULE = "rollbackModule"
    const val REGISTER_MODULE = "registerModule"
    const val RETIRE_MODULE = "retireModule"
    const val DELETE_MODULE = "deleteModule"
    const val SET_MODULE_ICON = "setModuleIcon"
    const val REORDER_TILES = "reorderTiles"

    override val definitions = listOf(
        ToolDefinition(
            name = CREATE_MODULE,
            description = "Create a blank module vessel — a room to build, fill and tend. Composition arrives later through saveModuleComposition.",
            category = "modules",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Room slug, lowercase_with_underscores"),
                ToolParameter("displayName", ToolParameterType.String, true, "Human-readable room name"),
                ToolParameter("description", ToolParameterType.String, true, "What this room is for"),
                ToolParameter("category", ToolParameterType.String, true, "User-worded category from the shape of their life (Home, Money, Comms, Travel...)")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_MODULES,
            description = "List every module room with its status and whether its door is showing in the lobby.",
            category = "modules",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GET_MODULE,
            description = "Get a module's full state: identity, composition, data space, revision.",
            category = "modules",
            parameters = listOf(ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = RENAME_MODULE,
            description = "Rename a module's display name in the lobby.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("displayName", ToolParameterType.String, true, "New display name")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SAVE_MODULE_COMPOSITION,
            description = "Save the room's composition (HTML, optional CSS and JS). Each save bumps the revision and snapshots history for rollback.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("html", ToolParameterType.String, true, "Room HTML body"),
                ToolParameter("css", ToolParameterType.String, false, "Room CSS, empty to skip"),
                ToolParameter("js", ToolParameterType.String, false, "Room JS, empty to skip")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SAVE_MODULE_DATA,
            description = "Persist the room's own data space as JSON. View-rooms read sovereign stores through tools instead; own-rooms keep their data here.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("dataJson", ToolParameterType.String, true, "The room's data as JSON")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GET_MODULE_DATA,
            description = "Read the room's own data space back.",
            category = "modules",
            parameters = listOf(ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GET_MODULE_REVISIONS,
            description = "List the room's composition revisions — every version ever saved, newest first.",
            category = "modules",
            parameters = listOf(ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = ROLLBACK_MODULE,
            description = "Restore a prior revision as the live composition. The restore itself saves as a new revision; history never rewrites.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("revision", ToolParameterType.String, true, "Revision number to restore")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = REGISTER_MODULE,
            description = "Register the room's door — it appears leading the lobby. Only after the human approves.",
            category = "modules",
            parameters = listOf(ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = RETIRE_MODULE,
            description = "Hide the room's door. Everything is preserved; it can be registered again any time.",
            category = "modules",
            parameters = listOf(ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_MODULE,
            description = "Permanently delete a module vessel and its revisions. ONLY after the human explicitly confirms deletion by name in conversation. Automation history survives.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("confirmed", ToolParameterType.String, true, "Must be the exact word 'DELETE' — set only when the human has explicitly confirmed")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SET_MODULE_ICON,
            description = "Set the room's lobby door icon from a saved local icon path.",
            category = "modules",
            parameters = listOf(
                ToolParameter("moduleId", ToolParameterType.String, true, "Module ID or name"),
                ToolParameter("iconPath", ToolParameterType.String, true, "Local file path from the icon tools")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = REORDER_TILES,
            description = "Persist a new unified lobby order. Provide the complete ordered tile list ('module:<id>' for grown rooms, 'static:<key>' for built-ins); partial lists strand tiles.",
            category = "modules",
            parameters = listOf(
                ToolParameter("orderedTileIds", ToolParameterType.String, true, "JSON array of every tile key in the new order")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ModuleToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}