package com.unuslumen.app.guru.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.unuslumen.app.util.shell.AppRuntimeExec

/**
 * FileOcrHelper — the whole-image OCR path for image attachments.
 *
 * Exactly the processFile executor's bundled tesseract pattern, extracted to
 * one helper both the ingest pipeline and any internal image OCR reads.
 * Images carrying no legible text carry an empty string: never a failure
 * state for a textless photo.
 */
object FileOcrHelper {

    /** Max long edge fed to tesseract for image attachments. */
    private const val OCR_MAX_EDGE_PX = 1600

    /**
     * Full-image bundled OCR. BitmapFactory decodes (HEIC/HEIF/JPEG/PNG/WebP
     * natively covered); a grayscale raster drops to a binary PGM P5 file
     * which leptonica reads without any codec. Returned text can be empty;
     * only a missing runtime returns null semantics through the empty call.
     */
    suspend fun ocrWholeImage(context: Context, file: java.io.File): String {
        if (!file.exists() || file.length() <= 0L) return ""
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

            // Binary PGM (P5): the one no-codec raster path the bundled engine reads.
            val pgm = java.io.File.createTempFile("guru_ocr_", ".pgm", context.cacheDir)
            java.io.FileOutputStream(pgm).use { out ->
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

            val provider = com.unuslumen.app.util.shell.TesseractProvider(context)
            val ready = provider.isAvailable() || provider.initialize()
            val outText = if (ready) {
                AppRuntimeExec.exec(
                    binaryPath = provider.binaryPath,
                    args = provider.ocrArgs(pgm.absolutePath, psm = 3),
                    env = provider.ocrEnv(),
                    timeoutSeconds = 60L
                ).stdout.trim()
            } else ""
            pgm.delete()
            outText
        } catch (e: Exception) {
            android.util.Log.w("guru_media", "Image OCR failed: ${e.message}")
            ""
        }
    }
}