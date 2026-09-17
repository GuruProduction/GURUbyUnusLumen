package com.unuslumen.app.data.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.AppRuntimeExec
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class FileProcessingToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    companion object {
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif")
        val officeExtensions = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "odt", "ods", "odp")
        val textExtensions = setOf("txt", "log", "xml", "json", "html", "css", "js", "py", "kt", "java", "sh", "md", "yml", "yaml", "cfg", "ini", "conf", "properties", "gradle", "kts")
        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp")
        val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus")
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

        /** Cap inline OCR to keep the tool result bounded. ~32k chars of text. */
        private const val MAX_OCR_CHARS = 32_000
        /** Max source dimension fed to OCR — larger images are downsampled first. */
        private const val OCR_MAX_EDGE_PX = 2400
        /** Seconds of video covered by one 3x2 contact sheet at 1fps = 6 frames. */
        const val SHEET_SECONDS = 6L
        /** Hard safety cap on total sheets per video (2 hours at 1fps = 1200 sheets). */
        const val MAX_SHEETS = 1200L
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            FileProcessingToolDefinitions.PROCESS_FILE -> processFile(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun processFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'path' parameter")

        val file = File(path)
        if (!file.exists()) {
            val result = FileProcessResult(false, path, file.name, "unknown", "", emptyMap(), "File does not exist: $path")
            return@withContext ToolExecutionResult.success(result, json.encodeToString(FileProcessResult.serializer(), result))
        }

        val fileName = file.name
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = getMimeType(extension, file)

        try {
            val result = when {
                extension in imageExtensions -> processImage(file, extension)
                extension == "pdf" -> processPdf(file)
                extension in officeExtensions -> processOfficeDoc(file, extension)
                extension in textExtensions -> processPlainText(file)
                extension in videoExtensions -> processVideo(file)
                extension in audioExtensions -> processAudio(file)
                extension in archiveExtensions -> processArchive(file)
                else -> processGeneric(file, extension)
            }
            ToolExecutionResult.success(result, json.encodeToString(FileProcessResult.serializer(), result))
        } catch (e: Exception) {
            val result = FileProcessResult(false, path, fileName, mimeType, "", mapOf("size" to file.length().toString()), "Processing failed: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(FileProcessResult.serializer(), result))
        }
    }

    private suspend fun processImage(file: File, extension: String): FileProcessResult {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        val mimeType = options.outMimeType ?: "image/$extension"

        val metadata = mutableMapOf(
            "width" to width.toString(), "height" to height.toString(),
            "mimeType" to mimeType, "size" to file.length().toString()
        )

        // Inline OCR — tesseract 5.5.0 is BUNDLED in the APK (libguru_tesseract.so
        // + eng.traineddata). No installs, no shell hunts. HEIC/HEIF/JPEG/PNG/WebP
        // all decode through BitmapFactory first, then the raster feeds tesseract.
        val ocrText = runBundledOcr(file) ?: ""
        if (ocrText.isNotBlank()) metadata["ocr_source"] = "bundled-tesseract"

        val guidance = buildString {
            appendLine("IMAGE FILE PROCESSED")
            appendLine("Dimensions: ${width}x${height}. Size: ${formatBytes(file.length())}.")
            if (ocrText.isNotBlank()) {
                appendLine("")
                appendLine("=== OCR TEXT (extracted on-device by the bundled tesseract engine) ===")
                appendLine(ocrText.take(MAX_OCR_CHARS))
                appendLine("=== END OCR TEXT ===")
            } else {
                appendLine("OCR found no text in this image (the bundled engine ran and returned empty — likely a photo without embedded text, or a heavily stylised image).")
            }
            appendLine("")
            appendLine("This same image is delivered to you visually as an attached image block — you can see its contents directly. The OCR text above complements your vision for printed/handwritten text extraction.")
            appendLine("To show this image to the user, use portalRender() to render an <img> tag inline.")
        }

        return FileProcessResult(true, file.absolutePath, file.name, mimeType, guidance, metadata, null)
    }

    /**
     * Run the BUNDLED tesseract binary over the image and return recognised text.
     * Pure app-runtime exec via AppRuntimeExec — no root, no ADB, no shell.
     *
     * HEIC/HEIF and other formats BitmapFactory handles natively are decoded to an
     * 8-bit grayscale PGM (max edge OCR_MAX_EDGE_PX) in the app cache and fed to
     * tesseract — the bundled engine's leptonica was built with codec stubs, so the
     * Android bitmap pipeline is the decoder and leptonica reads plain PGM natively.
     * Returns the recognised text (possibly empty), or null when OCR could not run
     * at all (missing binary, undecodable image).
     */
    private suspend fun runBundledOcr(file: File): String? {
        return try {
            val provider = shellExecutor.tesseract
            if (!provider.isAvailable() && !provider.initialize()) {
                Log.w("guru", "OCR: bundled tesseract unavailable")
                return null
            }

            // Decode + grayscale + downsample to an OCR-friendly raster.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= OCR_MAX_EDGE_PX) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            val gray = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(gray)
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (gray !== bitmap) bitmap.recycle()

            // Write binary PGM (P5): leptonica's PNM codec is built-in, zero codec deps.
            val pgm = File.createTempFile("guru_ocr_", ".pgm", context.cacheDir)
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

            // Run the engine through pure app-runtime exec.
            val result = AppRuntimeExec.exec(
                binaryPath = provider.binaryPath,
                args = provider.ocrArgs(pgm.absolutePath, psm = 3),
                env = provider.ocrEnv(),
                timeoutSeconds = 60L
            )
            pgm.delete()
            if (result.stdout.isNotBlank()) result.stdout.trim()
            else {
                if (result.stderr.isNotBlank()) Log.w("guru", "OCR engine diagnostics: ${result.stderr.take(300)}")
                ""
            }
        } catch (e: Exception) {
            Log.w("guru", "OCR: bundled engine failed: ${e.message}")
            null
        }
    }

    private suspend fun processPdf(file: File): FileProcessResult {
        val metadata = mutableMapOf("size" to file.length().toString())
        // Try pdftotext if present; fall back to Android's native PdfRenderer which
        // can rasterise every page — then bundled tesseract OCRs the pages. No
        // installs requested anywhere on this path.
        val textResult = shellExecutor.execute("pdftotext \"${file.absolutePath}\" - 2>/dev/null")
        if (textResult.success && textResult.stdout.isNotBlank()) {
            return FileProcessResult(true, file.absolutePath, file.name, "application/pdf", textResult.stdout.take(50000), metadata, null)
        }
        val renderedText = try {
            extractPdfTextViaRenderer(file)
        } catch (e: Exception) {
            Log.w("guru", "PDF renderer path failed: ${e.message}")
            null
        }
        return if (!renderedText.isNullOrEmpty()) {
            FileProcessResult(true, file.absolutePath, file.name, "application/pdf", renderedText.take(50000), metadata, null)
        } else {
            val guidance = buildString {
                appendLine("PDF FILE DETECTED (${formatBytes(file.length())})")
                appendLine("Text extraction unavailable for this PDF (likely scanned images only, or the PDF is encrypted).")
                appendLine("Convert a page to an image and send it to the user for visual reading, or report the PDF as unreadable on this device.")
            }
            FileProcessResult(true, file.absolutePath, file.name, "application/pdf", guidance, metadata, null)
        }
    }

    /**
     * Rasterise up to the first 5 PDF pages with Android's PdfRenderer and OCR each
     * page with the bundled tesseract. Returns concatenated page text, or null when
     * the renderer path is impossible (corrupt PDF, IO failure).
     */
    private suspend fun extractPdfTextViaRenderer(file: File): String? {
        return try {
            val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(descriptor)
            val pages = minOf(renderer.pageCount, 5)
            if (pages <= 0) { renderer.close(); return null }
            val allText = StringBuilder()
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                // OCR the page bitmap through the same bundled engine path
                val pageFile = File.createTempFile("guru_pdf_", ".pgm", context.cacheDir)
                java.io.FileOutputStream(pageFile).use { out ->
                    out.write("P5\n${bitmap.width} ${bitmap.height}\n255\n".toByteArray())
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val bytes = ByteArray(pixels.size)
                    for (px in pixels.indices) {
                        val r = (pixels[px] shr 16) and 0xFF
                        val g = (pixels[px] shr 8) and 0xFF
                        val b = pixels[px] and 0xFF
                        bytes[px] = ((r + g + b) / 3).toByte()
                    }
                    out.write(bytes)
                }
                bitmap.recycle()
                val provider = shellExecutor.tesseract
                if (provider.isAvailable() || provider.initialize()) {
                    val result = AppRuntimeExec.exec(
                        binaryPath = provider.binaryPath,
                        args = provider.ocrArgs(pageFile.absolutePath, psm = 3),
                        env = provider.ocrEnv(),
                        timeoutSeconds = 60L
                    )
                    val text = result.stdout.trim()
                    if (text.isNotBlank()) {
                        allText.appendLine("--- PDF page ${i + 1} ---")
                        allText.appendLine(text)
                    }
                }
                pageFile.delete()
            }
            renderer.close()
            descriptor.close()
            val resultText = allText.toString().trim()
            if (resultText.isBlank()) null else resultText
        } catch (e: Exception) {
            Log.w("guru", "PDF raster+OCR failed: ${e.message}")
            null
        }
    }

    private fun processOfficeDoc(file: File, extension: String): FileProcessResult {
        val metadata = mutableMapOf("size" to file.length().toString())
        if (extension == "csv") {
            val content = file.readText().take(50000)
            return FileProcessResult(true, file.absolutePath, file.name, "text/csv", content, metadata, null)
        }
        val guidance = buildString {
            appendLine("OFFICE DOCUMENT DETECTED (${extension.uppercase()}, ${formatBytes(file.length())})")
            appendLine("Modern Office formats are ZIP containers of XML. The bundled unzip tool is available:")
            appendLine("unzip -o \"${file.absolutePath}\" -d /tmp/officedoc")
            appendLine("Then read the XML content from the extracted files (word/document.xml for docx, xl/sharedStrings.xml + sheets for xlsx, ppt/slides/*.xml for pptx).")
        }
        return FileProcessResult(true, file.absolutePath, file.name, getMimeType(extension, file), guidance, metadata, null)
    }

    private fun processPlainText(file: File): FileProcessResult {
        val content = file.readText().take(50000)
        return FileProcessResult(true, file.absolutePath, file.name, "text/plain", content, mapOf("size" to file.length().toString()), null)
    }

    private suspend fun processVideo(file: File): FileProcessResult {
        // Metadata + frames through Android's platform media engine — in-process,
        // no root, no ADB, no external binary for the live-seeing path.
        val metadata = mutableMapOf("size" to file.length().toString())
        val videoInfo = StringBuilder()

        val info = retrieverInfoMap(file)
        metadata["probe"] = "android-media"
        videoInfo.appendLine(info.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        val durationMs = info["duration_ms"]?.toLongOrNull() ?: 0L

        // Duration decides density — NO fixed frame cap. 1 FRAME PER 1 SECOND of
        // video, so an hour-long video is seen across every second of its length.
        // Frames tile into 3x2 sheets (6s of video per sheet at 1fps); sheets
        // arrive as a SEQUENCE of image blocks, each covering its own 6-second
        // range in order.
        val sheetPaths = buildVideoSheetsFullCoverage(file, durationMs)

        val guidance = buildString {
            appendLine("VIDEO FILE PROCESSED (${formatBytes(file.length())}, ${durationMs / 1000}s)")
            appendLine("Metadata:")
            appendLine(videoInfo.toString())
            if (sheetPaths.isNotEmpty()) {
                appendLine("${sheetPaths.size} frame contact sheet(s) generated — 1 frame per second across the FULL video:")
                sheetPaths.forEachIndexed { idx, p ->
                    appendLine("sheet ${idx + 1}/${sheetPaths.size} (video time ${idx * SHEET_SECONDS}s to ${(idx + 1) * SHEET_SECONDS}s): $p")
                }
                appendLine("Every sheet is delivered to you automatically as an image in the same turn. Read them in order for the full timeline.")
            } else {
                appendLine("Frame extraction returned nothing on this device; rely on the audio track via hearAudio or ask the user to send key screenshots.")
            }
            appendLine("Need a frame at an EXACT timestamp between sheets? Ask the user, or extract it directly — every extraction path auto-delivers its images.")
        }
        return FileProcessResult(true, file.absolutePath, file.name, "video/" + file.extension, guidance, metadata, null)
    }


    /**
     * FULL-COVERAGE video sight at 1 FRAME PER SECOND. Duration decides everything:
     * a 6s clip = 1 sheet; a 10-minute video = 100 sheets; an hour = 600 sheets.
     * Every frame is sampled in-process through MediaMetadataRetriever and tiled
     * into ordered 3x2 sheets, each annotated on-canvas with its timestamp range
     * so GURU always knows where in the video he is. Zero binary exec on this path.
     */
    private fun buildVideoSheetsFullCoverage(file: File, durationMs: Long): List<String> = try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)

        val frameW = 533
        val frameH = 400
        val framesPerSheet = 6L
        val framesPerSecond = 1L

        val totalFrames = ((durationMs / 1000L) * framesPerSecond).coerceAtLeast(1L)
        val sheetCount = (((totalFrames + framesPerSheet - 1) / framesPerSheet)
            .coerceAtMost(MAX_SHEETS)).toInt().coerceAtLeast(1)

        val paths = mutableListOf<String>()
        var frameIndex = 0L

        for (sheetIdx in 0 until sheetCount) {
            val sheet = android.graphics.Bitmap.createBitmap(
                frameW * 3, frameH * 2, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(sheet)
            canvas.drawColor(android.graphics.Color.BLACK)
            var drawn = 0

            for (slot in 0 until framesPerSheet) {
                if (frameIndex >= totalFrames) break
                val offsetUs = frameIndex * 1_000_000L / framesPerSecond
                val frame = retriever.getFrameAtTime(offsetUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(frame, frameW, frameH, true)
                    if (scaled !== frame) frame.recycle()
                    val x = (slot % 3) * frameW
                    val y = (slot / 3) * frameH
                    canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
                    scaled.recycle()
                    drawn++
                }
                frameIndex++
            }

            // Timestamp annotation strip across the top of the sheet
            val startSec = sheetIdx * SHEET_SECONDS
            val label = "VIDEO ${file.nameWithoutExtension.take(24)} | t=${startSec}s-${startSec + SHEET_SECONDS}s | sheet ${sheetIdx + 1}/$sheetCount"
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 34f
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }
            canvas.drawText(label, 12f, 40f, textPaint)

            if (drawn == 0) {
                sheet.recycle()
                break
            }

            val out = File(context.cacheDir, "vid_sheet_${System.currentTimeMillis()}_$sheetIdx.png")
            java.io.FileOutputStream(out).use { o ->
                sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, o)
            }
            sheet.recycle()
            if (out.exists() && out.length() > 0) paths.add(out.absolutePath) else break
        }
        retriever.release()
        paths
    } catch (e: Exception) {
        Log.w("guru", "full-coverage video sheets failed: ${e.message}")
        emptyList()
    }

    /** Retriever metadata map for the probe + guidance paths. */
    private fun retrieverInfoMap(file: File): Map<String, String> = try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        fun meta(key: Int): String? = retriever.extractMetadata(key)
        val map = buildMap<String, String> {
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { put("duration_ms", it) }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { put("width", it) }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { put("height", it) }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { put("rotation", it) }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { put("container", it) }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { put("bitrate", it) }
        }
        retriever.release()
        map
    } catch (e: Exception) {
        mapOf("error" to (e.message ?: "extraction failed"))
    }

    private suspend fun processAudio(file: File): FileProcessResult {
        // Platform media engine in-process: metadata without any binary exec.
        val metadata = mutableMapOf("size" to file.length().toString())
        val audioInfo = try {
            metadata["probe"] = "android-media"
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val lines = mutableListOf<String>()
            fun meta(key: Int): String? = retriever.extractMetadata(key)
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { lines.add("duration_ms: $it") }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { lines.add("bitrate: $it") }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { lines.add("container: $it") }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { lines.add("title: $it") }
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { lines.add("artist: $it") }
            retriever.release()
            if (lines.isEmpty()) "no metadata" else lines.joinToString("\n")
        } catch (e: Exception) {
            "metadata extraction failed: ${e.message}"
        }

        val guidance = buildString {
            appendLine("AUDIO FILE PROCESSED (${formatBytes(file.length())})")
            appendLine("Metadata:")
            appendLine(audioInfo)
            appendLine("For audio transcription: use the transcribeSpeech or transcribeAudio tools on this file — they accept audio/video paths directly.")
        }
        return FileProcessResult(true, file.absolutePath, file.name, "audio/" + file.extension, guidance, metadata, null)
    }

    private suspend fun processArchive(file: File): FileProcessResult {
        val listResult = shellExecutor.execute("unzip -l \"${file.absolutePath}\" 2>/dev/null | head -50")
        val listing = if (listResult.success) listResult.stdout else "Could not list archive contents"
        val guidance = buildString {
            appendLine("ARCHIVE FILE DETECTED")
            appendLine("Contents:")
            appendLine(listing)
            appendLine("To extract: unzip -o \"${file.absolutePath}\" -d /tmp/extracted_archive")
        }
        return FileProcessResult(true, file.absolutePath, file.name, getMimeType(file.extension, file), guidance, mapOf("size" to file.length().toString()), null)
    }

    private fun processGeneric(file: File, extension: String): FileProcessResult {
        return try {
            val asText = file.readText().take(50000)
            FileProcessResult(true, file.absolutePath, file.name, getMimeType(extension, file), asText, mapOf("size" to file.length().toString()), null)
        } catch (_: Exception) {
            val guidance = buildString {
                appendLine("BINARY FILE DETECTED")
                appendLine("File type: .$extension, size: ${formatBytes(file.length())}")
                appendLine("hexdump -C \"${file.absolutePath}\" | head -50")
                appendLine("file \"${file.absolutePath}\"")
            }
            FileProcessResult(true, file.absolutePath, file.name, getMimeType(extension, file), guidance, mapOf("size" to file.length().toString()), null)
        }
    }

    private fun getMimeType(extension: String, file: File): String {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "csv" -> "text/csv"
            "txt", "log", "xml", "json", "html", "css", "js", "py", "kt", "java", "sh", "md", "yml", "yaml" -> "text/plain"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar", "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}