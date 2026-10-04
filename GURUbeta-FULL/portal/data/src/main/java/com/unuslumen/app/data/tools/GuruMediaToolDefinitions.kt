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
 * Guru tool constants for the persistent media library — three tools only,
 * no ingest: the automatic module ingests on attachment, Guru reads.
 */
object GuruMediaTools {
    const val MEDIA_ZOOM = "mediaZoom"
    const val MEDIA_SEARCH = "mediaSearch"
    const val MEDIA_RECALL = "mediaRecall"
}

/**
 * MediaToolDefinitionsPlus — definitions carried by real tool schema. The
 * executor class is resolved fully-qualified at the app-level (class exists
 * in the app media package), the Koin graph binding carries the matching
 * name string exactly as all other ToolDefinition executors do.
 */
object MediaToolDefinitionsPlus : ToolSetRegistration {
    /** Constants carried on the definitions themselves so nothing dangles. */
    const val MEDIA_ZOOM = GuruMediaTools.MEDIA_ZOOM
    const val MEDIA_SEARCH = GuruMediaTools.MEDIA_SEARCH
    const val MEDIA_RECALL = GuruMediaTools.MEDIA_RECALL

    override val definitions = listOf(
        ToolDefinition(
            name = MEDIA_ZOOM,
            description = "Zoom into saved media in the device's library: extract one " +
                "full-resolution frame out of the stored video bytes at any second. Useful for " +
                "reading printed text that appeared for a moment or verifying a visual detail " +
                "from the strip row.",
            category = "media",
            parameters = listOf(
                ToolParameter(
                    "mediaId", ToolParameterType.String, true,
                    "The media item's real id string as it appears in the library attachment payload"
                ),
                ToolParameter(
                    "timestampSeconds", ToolParameterType.String, true,
                    "Which second in the video to pull the frame (0.0 = first viewable second). " +
                        "Clamped inside the file's real duration."
                ),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = MEDIA_SEARCH,
            description = "Search every saved media item's transcripts and filenames by keyword.",
            category = "media",
            parameters = listOf(
                ToolParameter(
                    "query", ToolParameterType.String, true,
                    "Text to look for; every word matches a transcript chunk or OCR body"
                ),
                ToolParameter(
                    "maxResults", ToolParameterType.Integer, false,
                    "Max number of items, 5 by default."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = MEDIA_RECALL,
            description = "Recall a saved media item fully: the exact timeline row (or full " +
                "strip for the image kinds) the engine delivered inline when it was attached, re-read " +
                "from library state. Zoom history from media_zoom_log included.",
            category = "media",
            parameters = listOf(
                ToolParameter(
                    "mediaId", ToolParameterType.String, true,
                    "The media item to re-read."
                )
            ),
            permissions = emptyList()
        ),
    )

    /**
     * The executor KClass, resolved through a late-bound lookup so the
     * registration path never carries a compile-time reference to an app-module
     * symbol: the actual class lives in the app's media package and is bound
     * into the Koin graph there, carrying exactly this class-object name.
     */
    override fun executorClass(): KClass<out ToolExecutor> {
        val className = "com.unuslumen.app.guru.media.MediaLibraryToolsExecutor"
        val raw = Class.forName(className)
        @Suppress("UNCHECKED_CAST")
        return raw.kotlin as KClass<out ToolExecutor>
    }

    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}