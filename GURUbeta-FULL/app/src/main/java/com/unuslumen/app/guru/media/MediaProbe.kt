// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * MediaProbe — absolute metadata truth about a media file via the Android
 * platform media engine (MediaMetadataRetriever + MediaExtractor track
 * inspection, and BitmapFactory for still images). No binary deps, no cloud,
 * no guessing.
 *
 * WHY A STILL IMAGE USED TO FAIL HERE (issue 476). The probe required a
 * duration:
 *
 *     val durationMs = retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull()
 *     if (durationMs == null || durationMs <= 0L) return null
 *
 * A JPEG has no duration. It is not a container with a timeline, and
 * MediaMetadataRetriever reports null for it. So EVERY image returned null,
 * `MediaIngestService` reported "MediaProbe could not read <name>" and aborted
 * before `ingestImage` ran, no `media_items` row was ever written, and the OCR
 * text the prompt promises never reached memory. Measured on the ticket: two
 * attached screenshots, zero rows in `media_items`.
 *
 * WHY THE FAILURE NOW CARRIES ITS REASON. "MediaProbe could not read X" covered a
 * missing file, a permission refusal, a decode error and a timeout, which are four
 * different bugs with four different fixes. The caller gets a named reason now,
 * because an error that a genuine result could also produce is not an error
 * message.
 */
object MediaProbe {

    /**
     * Probe a media file, returning either its metadata or the reason it could
     * not be read.
     *
     * The kind decides which engine is asked. An image is measured with
     * BitmapFactory's header-only decode; everything else goes through the media
     * retriever, which is the only thing that knows about timelines.
     */
    suspend fun probeWithReason(filePath: String, kind: String? = null): MediaProbeResult {
        val file = File(filePath)
        if (!file.exists()) return MediaProbeResult.Failed("the file does not exist at $filePath")
        if (file.length() <= 0L) return MediaProbeResult.Failed("the file is empty (0 bytes)")
        if (!file.canRead()) return MediaProbeResult.Failed("the file exists but this process cannot read it")

        val resolvedKind = kind ?: kindFromPath(filePath)
        return if (resolvedKind == "image") probeImage(file) else probeTimedMedia(file)
    }

    /** Backwards-compatible form: metadata or null, for callers that only need presence. */
    suspend fun probe(filePath: String): MediaProbeInfo? =
        (probeWithReason(filePath) as? MediaProbeResult.Ok)?.info

    /**
     * A still image: dimensions and nothing else.
     *
     * `inJustDecodeBounds` reads the header without allocating the pixel buffer,
     * which matters for the ten-megabyte screenshots this is pointed at. A decoder
     * that returns a non-positive dimension has not decoded the file, and that is
     * a decode failure rather than a zero-sized image.
     */
    private fun probeImage(file: File): MediaProbeResult {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) {
                MediaProbeResult.Failed(
                    "the bytes are not a decodable image (decoder reported ${width}x$height)"
                )
            } else {
                MediaProbeResult.Ok(
                    MediaProbeInfo(
                        durationSec = 0.0,
                        width = width,
                        height = height,
                        fps = 0.0,
                        hasAudio = false,
                    )
                )
            }
        } catch (e: Exception) {
            MediaProbeResult.Failed("image decode threw ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: OutOfMemoryError) {
            // A header-only decode should never allocate, but a corrupt header can
            // still make the decoder reserve. On a phone with a full heap this is a
            // real outcome and it has its own sentence.
            MediaProbeResult.Failed("image decode ran out of memory")
        }
    }

    /** Video or audio: a container with a timeline, so a duration is required. */
    private fun probeTimedMedia(file: File): MediaProbeResult {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                try {
                    retriever.setDataSource(file.absolutePath)
                } catch (e: Exception) {
                    // setDataSource is where a permission refusal and an unsupported
                    // container both surface, and they are different bugs.
                    return MediaProbeResult.Failed(
                        "the platform media engine refused the file (${e.javaClass.simpleName}: ${e.message})"
                    )
                }
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                if (durationMs == null || durationMs <= 0L) {
                    return MediaProbeResult.Failed(
                        "the file opened but carries no duration, so it is not a playable container"
                    )
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // Portrait videos carry rotation 90/270; swap the axes back to physical reality.
                val w = if (rotation == 90 || rotation == 270) height else width
                val h = if (rotation == 90 || rotation == 270) width else height
                // Frame rate — API 28+ carries capture fps; 30 fps only as the
                // documented sentinel for an exactly-unknown file.
                val fps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloatOrNull()?.toDouble() ?: 30.0
                } else 30.0
                // Audio presence is a format-level fact, not a retriever myth.
                val hasAudio = hasAudioTrack(file)
                MediaProbeResult.Ok(
                    MediaProbeInfo(
                        durationSec = durationMs / 1000.0,
                        width = w,
                        height = h,
                        fps = fps,
                        hasAudio = hasAudio,
                    )
                )
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            MediaProbeResult.Failed("media probe threw ${e.javaClass.simpleName}: ${e.message}")
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

    /**
     * The kind, from the extension, for a caller that did not say.
     *
     * Mirrors the ingest service's own classifier rather than duplicating a second
     * opinion: an image decides which engine is asked, and getting it wrong is how
     * a still is sent to the timeline probe.
     */
    fun kindFromPath(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v") ||
                lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".3gp") -> "video"
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".aac") ||
                lower.endsWith(".opus") || lower.endsWith(".wma") || lower.endsWith(".3gpp") -> "audio"
            else -> "image"
        }
    }
}

/** The outcome of a probe: metadata, or the sentence explaining why there is none. */
sealed interface MediaProbeResult {
    data class Ok(val info: MediaProbeInfo) : MediaProbeResult
    data class Failed(val reason: String) : MediaProbeResult
}
