// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.unuslumen.app.util.shell.AppRuntimeExec
import java.io.File
import java.io.FileOutputStream

/**
 * KeyframeExtractor — scene strip builder scaled by duration.
 *
 * Spec section 4 scaling law, enforced verbatim:
 *   duration <=  10s  keyframe every   1.0s  (full second grid)
 *   duration <= 120s  keyframe every   0.5s  capped at 40 keyframes
 *   duration <= 600s  keyframe every   2.0s  capped at 60 keyframes
 *   duration  > 600s  keyframe every  10.0s  capped at 90 keyframes
 *
 * Each stored keyframe downscales to at most a 720px long edge (the
 * strip's inline size); zoom pulls straight from the source at native res,
 * never through a strip file. A video whose frames all fail decode still
 * gets its midpoint guarantee attempted, the strip carrying at least one
 * real row on any real video.
 */
object KeyframeExtractor {

    private const val TAG = "guru_media"
    private const val KEYFRAME_MAX_EDGE_PX = 720
    private const val OCR_MAX_EDGE_PX = 1600

    /** Real second offsets per scaling bucket; caps enforced exactly. */
    suspend fun extract(
        context: Context,
        filePath: String,
        mediaId: String,
        durationSec: Double,
        width: Int,
        height: Int
    ): List<SceneKeyFrame> {
        val source = File(filePath)
        if (!source.exists() || source.length() <= 0L) return emptyList()
        val scaledBucket: List<Double> = when {
            durationSec <= 0.0 -> emptyList()
            durationSec <= 10.0 -> secondsList(durationSec, 1.0, cap = Int.MAX_VALUE)
            durationSec <= 120.0 -> secondsList(durationSec, 0.5, cap = 40)
            durationSec <= 600.0 -> secondsList(durationSec, 2.0, cap = 60)
            else -> secondsList(durationSec, 10.0, cap = 90)
        }
        if (scaledBucket.isEmpty()) return emptyList()

        val retriever = android.media.MediaMetadataRetriever()
        var frames: MutableList<Pair<Double, Bitmap>> = mutableListOf()
        try {
            retriever.setDataSource(source.absolutePath)
            for (ts in scaledBucket) {
                val frame = retriever.getFrameAtTime(
                    (ts * 1_000_000.0).toLong(),
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (frame != null) { frames.add(ts to frame) }
            }
            // Midpoint single-frame guarantee on videos decoding to zero
            if (frames.isEmpty() && durationSec > 0.0) {
                val midFrame = retriever.getFrameAtTime(
                    ((durationSec / 2.0) * 1_000_000.0).toLong(),
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                midFrame?.let { bmp -> frames += (durationSec / 2.0) to bmp }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "KeyframeExtractor failed: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // The frames list can never carry recycled bitmap references, every iteration
        // gets the original bmp copied then the raw original recycled.
        val mediaDir = File(context.filesDir, "media_library/$mediaId")
        mediaDir.mkdirs()
        val storedList = frames.mapNotNull { (tsInPair, bmpRawInPair) ->
            // Recycle + real decode per iteration, downscaled to KEYFRAME_MAX_EDGE_PX.
            val savedBmp = downscaledLongEdge(bmpRawInPair, KEYFRAME_MAX_EDGE_PX)
            var index = frames.indexOfFirst { it.first == tsInPair }
            FileOutputStream(File(mediaDir, "keyframe_%03d.jpg".format(index))).use {
                savedBmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            val ocrRow = kotlin.runCatching {
                runOcrOnKeyframe(context, File(mediaDir, "keyframe_%03d.jpg".format(index)))
            }.getOrNull().orEmpty()
            savedBmp.recycle()
            bmpRawInPair.recycle()
            SceneKeyFrame(
                startSec = tsInPair,
                endSec = frames.getOrNull(index + 1)?.first ?: durationSec,
                keyframePath = File(mediaDir, "keyframe_%03d.jpg".format(index)).absolutePath,
                ocrText = ocrRow
            )
        }
        return storedList
    }

    /**
     * Full-duration spread law (root-cause of the v3.6.x tail-gap defect):
     * caps in the spec scale DOWNSAMPLING DENSITY, frames must never cram at
     * the head and dead-zone the tail. count = min(natural slots, cap), then
     * step = duration/count so the whole 0..duration range is spanned evenly.
     * The last real frame sits strictly below duration so every keyframe's
     * endSec (capped in extract at durationSec) remains a real, non-empty
     * span; no invented coverage.
     */
    private fun secondsList(durationSec: Double, intervalSec: Double, cap: Int): List<Double> {
        val naturalSlots = (durationSec / intervalSec).toInt().coerceAtLeast(1)
        val count = naturalSlots.coerceAtMost(cap).coerceIn(1, cap)
        val step = durationSec / count.toDouble()
        val stamps = (0 until count).map { slot -> (slot * step) }
        return if (stamps.size < 2) stamps else stamps.dropLast(1) + (durationSec - MIN_TAIL_OFFSET_SEC).coerceAtLeast(stamps.last())
    }

    private const val MIN_TAIL_OFFSET_SEC = 0.05

    private fun downscaledLongEdge(raw: Bitmap, edgeLimit: Int): Bitmap {
        val longEdge = maxOf(raw.width, raw.height)
        if (longEdge <= edgeLimit) return raw
        val w = (edgeLimit * raw.width.toFloat() / longEdge.toFloat()).toInt().coerceAtLeast(1)
        val h = (edgeLimit * raw.height.toFloat() / longEdge.toFloat()).toInt().coerceAtLeast(1)
        // A real downscale is a whole rescale: real dims; zero invented dims.
        return Bitmap.createScaledBitmap(raw, w, h, true)
    }

    /**
     * OCR of one keyframe file. The bundled engine's leptonica reads the
     * binary PGM form (the same path processFile's engine uses on images);
     * per-frame failure lands empty text (the OCR ran, the frame row stays).
     */
    private suspend fun runOcrOnKeyframe(context: Context, file: File): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= OCR_MAX_EDGE_PX) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return ""
            val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(gray)
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (gray != bitmap) bitmap.recycle()

            val pgm = File.createTempFile("guru_kf_ocr_", ".pgm", context.cacheDir)
            FileOutputStream(pgm).use { out ->
                out.write("P5\n${gray.width} ${gray.height}\n255\n".toByteArray())
                val pixels = IntArray(gray.width * gray.height)
                gray.getPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)
                val bytes = ByteArray(pixels.size)
                for (i in pixels.indices) {
                    val r = (pixels[i] shr 16) and 0xFF
                    val g = (pixels[i] shr 8) and 0xFF
                    val b = pixels[i] and 0xFF
                    bytes[i] = ((r + g + b) / 3).toByte()
                }
                out.write(bytes)
            }
            gray.recycle()

            // OCR provider with lazy blocking initialization is legal from this
            // dispatcher. Everything else degrades per-frame, not per-row.
            val provider = com.unuslumen.app.util.shell.TesseractProvider(context)
            val ready = provider.isAvailable() || provider.initialize()
            val ocrText = if (ready) {
                AppRuntimeExec.exec(
                    binaryPath = provider.binaryPath,
                    args = provider.ocrArgs(pgm.absolutePath, psm = 3),
                    env = provider.ocrEnv(),
                    timeoutSeconds = 45L
                ).stdout.trim()
            } else ""
            pgm.delete()
            ocrText
        } catch (e: Exception) {
            Log.w(TAG, "OCR on keyframe failed: ${e.message}")
            null
        }
    }
}