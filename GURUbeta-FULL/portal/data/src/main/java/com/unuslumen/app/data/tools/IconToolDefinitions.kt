// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Icon capability — the numen's hands for dressing its doors.
 *
 * A grown room with a hand-picked icon is the whole point: doors should
 * look like they belong to the life they serve. Downloads ride the same
 * sovereign egress as everything else (Tor or fail-closed), icons land in
 * the app's own storage, and every icon is validated for format and size
 * before it can become part of the app's face.
 */

@Serializable
data class DownloadIconResult(
    val success: Boolean,
    val iconPath: String? = null,
    val name: String,
    val sizeBytes: Long = 0,
    val error: String? = null
) : ToolResultData

@Serializable
data class ImportIconResult(
    val success: Boolean,
    val iconPath: String? = null,
    val name: String,
    val sizeBytes: Long = 0,
    val error: String? = null
) : ToolResultData

@Serializable
data class SavedIconsResult(
    val icons: List<SavedIcon>
) : ToolResultData {
    @Serializable
    data class SavedIcon(val name: String, val path: String, val sizeBytes: Long) : ToolResultData
}

@Serializable
data class DeleteIconResult(
    val name: String,
    val deleted: Boolean
) : ToolResultData

object IconToolDefinitions : ToolSetRegistration {
    const val DOWNLOAD_ICON = "downloadIcon"
    const val IMPORT_ICON_FROM_FILE = "importIconFromFile"
    const val LIST_SAVED_ICONS = "listSavedIcons"
    const val DELETE_SAVED_ICON = "deleteSavedIcon"

    override val definitions = listOf(
        ToolDefinition(
            name = DOWNLOAD_ICON,
            description = "Download an image from a URL and save it as a reusable icon for module doors and room decoration. Rides the app's Tor egress like all outbound traffic.",
            category = "modules",
            parameters = listOf(
                ToolParameter("url", ToolParameterType.String, true, "Direct image URL (png, jpg, gif, webp, svg)"),
                ToolParameter("name", ToolParameterType.String, true, "Icon slug, lowercase_with_underscores")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = IMPORT_ICON_FROM_FILE,
            description = "Import an image file already on the device (media library, attachments) as a saved icon.",
            category = "modules",
            parameters = listOf(
                ToolParameter("filePath", ToolParameterType.String, true, "Absolute local path to the image file"),
                ToolParameter("name", ToolParameterType.String, true, "Icon slug, lowercase_with_underscores")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_SAVED_ICONS,
            description = "List every saved icon available for doors and decoration.",
            category = "modules",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_SAVED_ICON,
            description = "Delete a saved icon file. Modules using it fall back to the default door art.",
            category = "modules",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Icon slug to delete")),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = IconToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}