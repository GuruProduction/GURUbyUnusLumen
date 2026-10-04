// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * GuruMediaTools — the three tool definitions the Guru engine gets:
 * mediaZoom (full-res frame out of a saved item at a second), mediaSearch
 * (FTS across transcripts, OCR text, and filenames) and mediaRecall (the
 * full strip document of one saved item re-rendered into the chat on his
 * request). No ingest tool: only these three, by design.
 */
object GuruMediaTools {
    const val MEDIA_ZOOM = "mediaZoom"
    const val MEDIA_SEARCH = "mediaSearch"
    const val MEDIA_RECALL = "mediaRecall"
}

/**
 * The registration object riding the KSP tool set registry chain
 * (exactly like all other existing sets such as MediaToolDefinitions).
 */
object MediaToolDefinitionsPlus : ToolSetRegistration {
    override val definitions = listOf(
        ToolDefinition(
            name = GuruMediaTools.MEDIA_ZOOM,
            description = "Zoom into a saved media file: extract a full-resolution frame at " +
                "any second. Media must already exist in the library (it does automatically when " +
                "any video was shared into the chat). Returns the frame path plus width and height.",
            category = "media",
            parameters = listOf(
                ToolParameter("mediaId", ToolParameterType.String, true, "The media file's ID from the library."),
                ToolParameter("timestampSeconds", ToolParameterType.String, true, "Second offset in the video (integer or decimal like 12.5).")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GuruMediaTools.MEDIA_SEARCH,
            description = "Search the saved media library with fulltext over transcript words, " +
                "keyframe OCR text and file names. Returns one row per media item per FTS hit.",
            category = "media",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, true, "Words to search for in the transcriptions or OCR text."),
                ToolParameter(
                    "maxResults", ToolParameterType.Integer, false,
                    "How many rows to return (default 5, max 20)."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GuruMediaTools.MEDIA_RECALL,
            description = "Recall one saved media's full timeline: chunk rows plus keyframe OCR " +
                "rows as one text document. For re-reading a strip already sent, e.g. when the user asks what happened last Monday in their video.",
            category = "media",
            parameters = listOf(
                ToolParameter("mediaId", ToolParameterType.String, true, "The media file's ID from mediaSearch or the attachment payload.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = MediaLibraryToolsExecutor::class

    override fun extractorClass(): KClass<out com.unuslumen.app.data.tools.registry.ToolResultExtractor>? = null
}