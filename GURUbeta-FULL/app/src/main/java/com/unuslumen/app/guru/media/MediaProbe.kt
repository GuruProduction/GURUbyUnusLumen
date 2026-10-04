// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * MediaProbe — absolute metadata truth about a media file via the Android
 * platform media engine (MediaMetadataRetriever + MediaExtractor track
 * inspection). No binary deps, no cloud, no guessing. Returns null when the
 * file can not be probed at all (missing, corrupt, zero-duration container).
 */
object MediaProbe {

    suspend fun probe(filePath: String): MediaProbeInfo? {
        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val retriever = MediaMetadataRetriever()
            val info = try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (durationMs == null || durationMs <= 0L) return null
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // Portrait videos carry rotation 90/270; swap the axes back to physical reality.
                val w = if (rotation == 90 || rotation == 270) height else width
                val h = if (rotation == 90 || rotation == 270) width else height
                // Frame rate — API 28+ carries capture fps; fall back to 30 fps sentinel
                // only for exact unknown files so scaling buckets still work.
                val fps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toDouble() ?: 30.0
                } else 30.0
                // Audio presence is a format-level fact, not a retriever myth.
                val hasAudio = hasAudioTrack(file)
                MediaProbeInfo(
                    durationSec = durationMs / 1000.0,
                    width = w,
                    height = h,
                    fps = fps,
                    hasAudio = hasAudio
                )
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            info
        } catch (_: Exception) {
            null
        }
    }

    /** Extractor-based audio track detection. False when the container is video-only or silent. */
    private fun hasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var found = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { found = true; break }
            }
            extractor.release()
            found
        } catch (_: Exception) {
            try { extractor.release() } catch (_: Exception) {}
            false
        }
    }
}