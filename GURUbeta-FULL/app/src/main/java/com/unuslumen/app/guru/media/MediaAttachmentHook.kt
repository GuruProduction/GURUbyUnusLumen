package com.unuslumen.app.guru.media

import android.content.Context
import android.util.Log
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.database.dao.MediaLibraryDao
import com.unuslumen.app.database.dao.MediaZoomLogDao

/**
 * MediaAttachmentHook — the wiring that turns every media attachment into a
 * library row and a Guru-readable strip document.
 *
 * Two live inputs:
 *
 * 1. ATTACHMENT INGEST — called by MediaIngestService for every File
 *    attachment before the chat engine reads the message. Non-media
 *    attachments (PDFs, notes, etc) ride straight through untouched.
 *    The strip text does not need to mutate the AiMessage itself: the module
 *    contract (plan r2 amendment) says the strip is delivered through the
 *    attachmentsText the ViewModel composes, which reads this layer.
 *
 * 2. ONE-TIME BACKFILL — one-time boot scan of every old cache file still
 *    sitting in cache/attached_files. Idempotent through sha256: files
 *    already in the library skip rebuild, only the missing ones get rows.
 */
class MediaAttachmentHook(
    private val context: Context,
    private val mediaLibraryDao: MediaLibraryDao,
    private val zoomLogDao: MediaZoomLogDao
) {
    companion object {
        private const val TAG = "guru_media"
    }

    /** The real ingest path this hook drives. Real service, real DAO. */
    val ingestService: MediaIngestService by lazy {
        MediaIngestService(context, mediaLibraryDao)
    }

    /** Real document provider (MediaDeliveryPort) that assembles the strips delivered in chat. */
    val deliveryTextProvider: MediaDeliveryTextProvider by lazy {
        MediaDeliveryTextProvider(context, ingestService)
    }

    /** Real repo the tools and UI both read through (shared instance). */
    val libraryRepository: MediaLibraryRepository by lazy {
        MediaLibraryRepository(mediaLibraryDao, zoomLogDao)
    }

    /**
     * The ingest of every File attachment in a user message's media (a
     * full list for multiple attachments, no attachments leaves untouched).
     * Idempotent through byte hash so re-attaching the exact same file
     * never re-renders a new row.
     */
    suspend fun onMediaMessage(userMessage: AiMessage.UserMessage): AiMessage.UserMessage {
        val mediaAttachments = userMessage.attachments.filterIsInstance<AiMessageAttachment.File>()
            .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/") }
        if (mediaAttachments.isEmpty()) return userMessage
        val ingested = mutableListOf<IngestResult>()
        android.util.Log.d(TAG, "Media message with ${mediaAttachments.size} media attachment(s), ingesting into library...")
        for (media in mediaAttachments) {
            val ingestedOne = runCatching {
                ingestService.ingest(media.cachedPath, media.fileName, media.mimeType)
            }.getOrElse { e ->
                android.util.Log.e(TAG, "Attachment ${media.fileName} ingest threw:", e)
                null
            } ?: continue
            if (ingestedOne.didSkipRebuild) {
                android.util.Log.d(TAG, "Media was already in the library, skipped rebuild for sha256 duplicate: ${ingestedOne.mediaId}")
            }
            if (!ingestedOne.didSkipRebuild && ingestedOne.status == "INGESTED") {
                android.util.Log.i(TAG, "Attachment OK: kind=${ingestedOne.mediaKind} " +
                    "duration=${"%.1f".format(ingestedOne.durationSec)}s id=${ingestedOne.mediaId} file=${media.fileName}")
            }
            if (ingestedOne.status == "FAILED") {
                android.util.Log.w(TAG, "Attachment INGEST FAILED (file = ${media.fileName}): ${ingestedOne.errorMessage}")
            }
            ingested += ingestedOne
        }
        // On library items there is no message mutation for a file; the strip
        // delivery rides attachmentsText and MediaDeliveryTextProvider below.
        return userMessage
    }

    /** Real cache backfill for attachments previously skipped by the module. */
    suspend fun backfillAttachedCache() {
        val attachmentCache = java.io.File(context.cacheDir, "attached_files")
        val candidates: List<java.io.File> = if (attachmentCache.exists()) {
            attachmentCache.listFiles().orEmpty().filter { it.isFile }.toList()
        } else emptyList()
        if (candidates.isEmpty()) {
            android.util.Log.d(TAG, "Backfill found nothing in the attachment cache")
            return
        }
        for (cachedFile in candidates) {
            val kind = mapFileToKind(cachedFile)
            if (kind == "unknown") {
                android.util.Log.d(TAG, "Backfill skips non-media cache file: ${cachedFile.name}")
                continue
            }
            val mimeType = when (kind) {
                "image" -> "image/${cachedFile.extension}"
                "video" -> "video/${cachedFile.extension}"
                "audio" -> "audio/${cachedFile.extension}"
                else -> "invalid-non-media"
            }
            if (mimeType == "invalid-non-media") continue
            val result = runCatching {
                ingestService.ingest(cachedFile.absolutePath, cachedFile.name, mimeType)
            }.getOrNull()
            when {
                result != null && result.didSkipRebuild ->
                    android.util.Log.d(TAG, "Backfill skipped: library already holds a sha256 duplicate (${result.mediaId})")
                result != null && result.status == "INGESTED" ->
                    android.util.Log.i(TAG, "Backfill ingested ${cachedFile.name} with mediaId = ${result.mediaId}")
                result != null && result.status == "FAILED" ->
                    android.util.Log.w(TAG, "Backfill FAILED (file = ${cachedFile.name}): ${result.errorMessage}")
                else -> android.util.Log.w(TAG, "Backfill returned nothing real for ${cachedFile.name}")
            }
        }
    }

    /** Real extension mapping for file kinds — image/video/audio or unknown. */
    private fun mapFileToKind(file: java.io.File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
            "avif", "tiff", "tif" -> "image"
            "mp4", "mov", "m4v", "webm", "mkv", "3gp", "avi", "flv" -> "video"
            "mp3", "wav", "m4a", "flac", "ogg", "aac", "opus", "wma", "amr", "3gpp" -> "audio"
            else -> "unknown"
        }
    }
}