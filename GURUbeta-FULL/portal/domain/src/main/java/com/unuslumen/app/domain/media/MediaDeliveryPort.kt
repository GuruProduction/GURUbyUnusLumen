// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.media

import com.unuslumen.app.domain.model.AiMessage

/**
 * MediaDeliveryPort — the boundary the presentation layer sees for the media
 * module. Every strip document (keyframe strip + transcripts) a media item
 * produced lands in `attachmentsText` by exactly one call:
 *
 *    buildDeliveryText(message)
 *
 * delivered from Koin with the media module's real implementation. The
 * implementation lives in the app source tree (com.unuslumen.app.guru.media);
 * the port lives in domain so portal:presentation carries no app-package
 * dependency.
 */
fun interface MediaDeliveryPort {
    /**
     * Build strip documents for the media attachments riding this one message.
     * Real ingest runs through the module; a blank string arrives when the
     * message carries no media at all, so the presentation layer keeps its
     * original text unchanged.
     */
    suspend fun buildDeliveryText(message: AiMessage.UserMessage): String
}