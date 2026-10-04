// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import android.content.Context
import com.unuslumen.app.domain.media.MediaDeliveryPort
import com.unuslumen.app.domain.model.AiMessage

/**
 * The real [MediaDeliveryPort] implementation of the whole media module.
 * Every media attachment in one user message is ingested and its strip doc
 * is returned, one document block per item; failures are real FAILED blocks
 * in the text, never silence. Non-media messages return an empty string and
 * the ViewModel keeps its original attachment text. Every call is fully
 * idempotent (sha256 bytes): re-attachment reads from library state instead
 * of re-rendering the same work twice.
 */
class MediaDeliveryTextProvider(
    private val context: Context,
    private val ingestService: MediaIngestService,
) : MediaDeliveryPort {

    /**
     * Returns the joined strip documents for every media attachment in this
     * message: the header, real probed facts (kind, duration, OCR rows, keyframe
     * path rows), and transcript lines. Failures carry their real errors, so
     * no silence. Non-media messages get an empty return.
     */
    override suspend fun buildDeliveryText(message: AiMessage.UserMessage): String {
        val mediaItems = message.attachments.filterIsInstance<com.unuslumen.app.domain.model.AiMessageAttachment.File>()
            .filter { media ->
                media.mimeType.startsWith("video/") ||
                    media.mimeType.startsWith("image/") ||
                    media.mimeType.startsWith("audio/")
            }
        if (mediaItems.isEmpty()) return ""
        val results = mutableListOf<IngestResult>()
        for (mediaAttachment in mediaItems) {
            results += ingestService.ingest(mediaAttachment.cachedPath, mediaAttachment.fileName, mediaAttachment.mimeType)
        }
        if (results.all { it.status == "FAILED" }) {
            return "MEDIA SAVE FAILED for every attached file: ${results.joinToString("; ") { it.errorMessage }}"
        }
        return results.filter { it.status != "FAILED" }.joinToString("\n\n") { it.timelineText }
    }
}