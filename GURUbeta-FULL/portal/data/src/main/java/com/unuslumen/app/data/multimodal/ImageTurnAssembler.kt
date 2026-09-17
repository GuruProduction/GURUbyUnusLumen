package com.unuslumen.app.data.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.unuslumen.app.database.dao.SeenImageDao
import com.unuslumen.app.database.entity.SeenImageEntity
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Multimodal content plumbing — the middleware that makes the engine actually SEE media.
 *
 * Tool results like takeScreenshot / cameraCapture / videoFrame return file paths as plain
 * JSON strings. Nothing ever sent those files to the model, so the engine could never see
 * what was captured. This assembler fixes that.
 *
 * Before each model request, after microcompact:
 *   1. Scan the current turn's tool results for file paths ending in .jpg/.jpeg/.png/.webp
 *      (images) and .mp4/.mov (videos).
 *   2. For images: read from disk, downscale to max 1600px long edge, JPEG q80, base64
 *      encode as a data URI.
 *   3. For videos: the engine's video capability is not confirmed, so chunk to keyframes
 *      into a 1fps 6-frame tiled contact sheet (ffmpeg on device). This produces a single
 *      image that represents the video — spec point 3 fallback.
 *   4. Scan the current user message text for absolute media paths the human referenced
 *      directly ("look at this /storage/emulated/0/DCIM/x.jpg") — same treatment.
 *   5. Cap at 4 images per request to protect context.
 *   6. Record every injected path in seen_images so later turns can reference earlier
 *      images by path, and re-include referenced earlier images (also capped at 4 total).
 *
 * The output is a map from message key to image blocks. UnusLumenStreamingClient consumes
 * this map when serialising the request, attaching image blocks next to the text content
 * of the matching message.
 */
class ImageTurnAssembler(
    private val context: Context,
    private val seenImageDao: SeenImageDao,
    private val shellExecutor: ShellExecutor
) {

    /** Screen sight — grabs the user's current screen when vision is enabled. Lazy so
     *  no projection/session machinery exists until vision is first used. */
    private val sightController by lazy { ScreenSightController(context) }

    /** Set from AiRepositoryImpl each request: is vision_enabled on? */
    @Volatile
    var visionEnabled: Boolean = false

    companion object {
        private const val TAG = "guru"

        private const val MAX_IMAGES_PER_TURN = 4

        /** Max long-edge dimension after downscale (spec: 1600px). */
        private const val MAX_EDGE_PX = 1600

        /** JPEG quality for re-encode (spec: q80). */
        private const val JPEG_QUALITY = 80

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")

        /** MIME type of every image block we send — all media lands as JPEG data URIs. */
        const val IMAGE_URI_MIME = "image/jpeg"

        /** Key for image blocks attached to the user message itself. */
        const val USER_KEY = "user"

        private val MEDIA_PATH_REGEX = Regex(
            """(?:^|[\s"'=(:])((?:/|content://|file://)[\w@\-./+()%]*\.(?:jpg|jpeg|png|webp|heic|heif|bmp|gif|mp4|mov|m4v|webm|mkv|3gp))""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Encode cache: (path, lastModified, length) → encoded ImageBlock.
         * A single photo re-encoded on every do/while iteration and every model
         * retry burned seconds per pass; with the cache it encodes once per
         * file version. Bounded at 24 entries, LRU by insertion order.
         */
        private const val ENCODE_CACHE_MAX = 24
        private val encodeCache = object : LinkedHashMap<String, ImageBlock>(ENCODE_CACHE_MAX, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBlock>?): Boolean =
                size > ENCODE_CACHE_MAX
        }
    }

    /** A single image block the streaming client serialises into the request. */
    data class ImageBlock(
        val dataUri: String,
        val sourcePath: String
    )

    /** Result of assembling one request's image set. */
    data class AssembledImages(
        /** Message key (tool-call uuid, or [USER_KEY]) → image blocks for that message. */
        val byMessage: Map<String, List<ImageBlock>>,
        /** Every path newly injected this request, for seen_images bookkeeping. */
        val injectedPaths: List<String>,
        /** Total image blocks attached (for logging). */
        val totalImages: Int,
        /** True when vision was enabled but no screen frame could be captured this request. */
        val captureFailed: Boolean = false
    )

    /**
     * Main entry. Call with the post-microcompact message list. Only the CURRENT TURN is
     * scanned for fresh captures; earlier images return only when the human's message
     * references their path (via seen_images).
     */
    suspend fun assemble(messages: List<AiMessage>): AssembledImages = withContext(Dispatchers.IO) {
        val lastUserIndex = messages.indexOfLast { it is AiMessage.UserMessage }
        if (lastUserIndex == -1) return@withContext EMPTY

        val userMessage = messages[lastUserIndex] as AiMessage.UserMessage
        val turnMessages = messages.subList(lastUserIndex, messages.size)

        val blocksByMessage = LinkedHashMap<String, MutableList<ImageBlock>>()
        val injectedPaths = mutableListOf<String>()
        var remaining = MAX_IMAGES_PER_TURN
        var captureFailed = false

        // --- 0. Vision: attach the current screen when enabled ---
        if (visionEnabled) {
            try {
                val screen = sightController.grabScreen()
                if (screen != null) {
                    val block = encodeBitmap(screen, "screen")
                    blocksByMessage.getOrPut(USER_KEY) { mutableListOf() }.add(block)
                    injectedPaths.add("screen://live")
                    remaining--
                    android.util.Log.d(TAG, "Sight: screen frame attached (source=${if (sightController.lastSourceWasProjection) "projection" else "screencap"})")
                } else {
                    captureFailed = true
                    android.util.Log.w(TAG, "Sight: enabled but no frame available (no consent, or screencap unavailable)")
                }
            } catch (e: Exception) {
                captureFailed = true
                android.util.Log.w(TAG, "Sight: grab failed: ${e.message}")
            }
        }

        // --- 1. Typed attachments FIRST (authoritative, no regex mining): files the
        // user explicitly attached arrive as AiMessageAttachment.File with a cached
        // path — these are ALWAYS considered, regex or not.
        val attachmentPaths = userMessage.attachments.filterIsInstance<com.unuslumen.app.domain.model.AiMessageAttachment.File>()
            .map { File(it.cachedPath) }
        val userPaths = LinkedHashSet<String>()
        for (file in attachmentPaths) {
            if (remaining <= 0) break
            if (!file.exists() || file.length() == 0L) continue
            val block = encodeMedia(file) ?: continue
            blocksByMessage.getOrPut(USER_KEY) { mutableListOf() }.add(block)
            injectedPaths.add(file.absolutePath)
            remaining--
        }

        // --- 1b. Paths the human referenced directly in their message text ---
        userPaths.addAll(extractMediaPaths(userMessage.content + userMessage.attachmentsText))
        for (rawPath in userPaths) {
            if (remaining <= 0) break
            if (injectedPaths.contains(resolveUserPath(rawPath).absolutePath)) continue
            val file = resolveUserPath(rawPath)
            if (!file.exists() || file.length() == 0L) continue
            val block = encodeMedia(file) ?: continue
            blocksByMessage.getOrPut(USER_KEY) { mutableListOf() }.add(block)
            injectedPaths.add(file.absolutePath)
            remaining--
        }

        // --- 2. Tool results in the current turn ---
        // Keyed by the LLM tool-call id (AiMessage.ToolCall.id) — that is the id koog
        // carries into Message.Tool.Result, so the streaming client can match the
        // image blocks to the right tool result message.
        for (msg in turnMessages) {
            if (remaining <= 0) break
            if (msg !is AiMessage.ToolCall) continue
            val callId = msg.id ?: continue
            if (blocksByMessage.containsKey(callId)) continue
            val paths = extractMediaPaths(msg.resultRawContent)
            for (path in paths) {
                if (remaining <= 0) break
                val file = File(path)
                if (!file.exists() || file.length() == 0L) continue
                val block = encodeMedia(file) ?: continue
                blocksByMessage.getOrPut(callId) { mutableListOf() }.add(block)
                injectedPaths.add(file.absolutePath)
                remaining--
            }
        }

        if (blocksByMessage.isEmpty()) {
            return@withContext EMPTY
        }

        // --- 3. Record what the engine saw, for later turns' reference-by-path ---
        recordSeenImages(turnMessages, userPaths.toList(), injectedPaths)

        // --- 4. Earlier images the human referenced by path (from seen_images) ---
        for (rawPath in userPaths) {
            if (remaining <= 0) break
            if (injectedPaths.contains(resolveUserPath(rawPath).absolutePath)) continue
            val seen = try { seenImageDao.getByPath(resolveUserPath(rawPath).absolutePath) } catch (e: Exception) { null }
            if (seen == null) continue
            val file = resolveUserPath(rawPath)
            if (!file.exists() || file.length() == 0L) continue
            val block = encodeMedia(file) ?: continue
            blocksByMessage.getOrPut(USER_KEY) { mutableListOf() }.add(block)
            injectedPaths.add(file.absolutePath)
            remaining--
        }

        AssembledImages(
            byMessage = blocksByMessage,
            injectedPaths = injectedPaths,
            totalImages = blocksByMessage.values.sumOf { it.size },
            captureFailed = captureFailed
        )
    }

    /** Sentinel empty result. */
    val EMPTY: AssembledImages = AssembledImages(emptyMap(), emptyList(), 0, captureFailed = false)

    /**
     * Extract absolute media paths from arbitrary text (tool result JSON or user text).
     */
    internal fun extractMediaPaths(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val unescaped = text.replace("\\/", "/")
        val results = LinkedHashSet<String>()
        for (match in MEDIA_PATH_REGEX.findAll(unescaped)) {
            val path = match.groupValues[1].trimEnd('.', ',')
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS) {
                results.add(path)
            }
        }
        return results.toList()
    }

    /**
     * Encode one media file into an ImageBlock, with a per-file-version cache.
     * Cache key includes lastModified + length so edited files re-encode.
     * Mutex (not @Synchronized — which is illegal on suspend functions) so
     * concurrent tool-loop coroutines encoding the same file produce one entry.
     */
    private val encodeMutex = Mutex()
    internal suspend fun encodeMedia(file: File): ImageBlock? = encodeMutex.withLock {
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        encodeCache[key]?.let { return@withLock it }
        val ext = file.extension.lowercase()
        val block = when {
            ext in IMAGE_EXTENSIONS -> encodeImage(file)
            ext in VIDEO_EXTENSIONS -> encodeVideoSheet(file)
            else -> null
        }
        if (block != null) {
            encodeCache[key] = block
        }
        block
    }

    /** Encode an in-memory Bitmap (screen frames) into an ImageBlock. */
    internal fun encodeBitmap(bitmap: Bitmap, sourceLabel: String): ImageBlock {
        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (s !== bitmap) bitmap.recycle()
            s
        } else bitmap

        val jpegBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        }
        val dataUri = "data:$IMAGE_URI_MIME;base64," +
            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return ImageBlock(dataUri = dataUri, sourcePath = sourceLabel)
    }

    private fun encodeImage(file: File): ImageBlock? {
        val raw: ByteArray = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "ImageAssembler: cannot read ${file.name}: ${e.message}")
            return null
        }
        if (raw.isEmpty()) return null

        // Bounds-decode first pass to learn dimensions without loading full pixels
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "ImageAssembler: undecodable image ${file.name}")
            return null
        }

        // Sample so the decoded bitmap sits near (but not below) the target edge
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            ?: return null

        // Final exact-fit downscale if still over the cap
        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (s !== bitmap) bitmap.recycle()
            s
        } else bitmap

        val jpegBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        }

        val dataUri = "data:$IMAGE_URI_MIME;base64," +
            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return ImageBlock(dataUri = dataUri, sourcePath = file.absolutePath)
    }

    /**
     * Video path: 6-frame contact sheet as ONE image block, rendered by the
     * BUNDLED ffmpeg (libguru_ffmpeg.so via FfmpegProvider). The bare "ffmpeg"
     * from the old code did not exist on PATH and every video fumbled. When no
     * ffmpeg binary exists at all, fall back to Android MediaMetadataRetriever
     * to pull ONE frame natively so video still yields sight on any device.
     */
    private suspend fun encodeVideoSheet(file: File): ImageBlock? {
        val frameDir = File(context.cacheDir, "guru_video_frames")
        frameDir.mkdirs()
        val sheetFile = File(frameDir, "sheet_${System.currentTimeMillis()}_${file.nameWithoutExtension}.png")
        val sheetPath = sheetFile.absolutePath.escapeShell()
        val videoPath = file.absolutePath.escapeShell()

        val ffmpegPath = com.unuslumen.app.util.shell.FfmpegProvider.ffmpegBinaryPath(context)
        val sheet: File? = if (ffmpegPath != null) {
            // 1 fps sampling, tile 3x2 at ~533px per frame width → ~1600px wide composite
            val cmd = "'$ffmpegPath' -y -i $videoPath -vf \"fps=1,scale=533:-1,tile=3x2,setsar=1\" -frames:v 1 $sheetPath"
            val result = shellExecutor.execute(cmd)
            if (result.success && sheetFile.exists() && sheetFile.length() > 0L) sheetFile else {
                Log.w(TAG, "ImageAssembler: bundled ffmpeg sheet failed for ${file.name}: ${result.stderr.take(200)}")
                null
            }
        } else {
            // Platform fallback: grab one representative frame via MediaMetadataRetriever
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (bitmap != null) {
                    java.io.FileOutputStream(sheetFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    bitmap.recycle()
                    if (sheetFile.exists() && sheetFile.length() > 0L) sheetFile else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "ImageAssembler: retriever frame failed for ${file.name}: ${e.message}")
                null
            }
        }

        if (sheet == null) {
            cleanupDir(frameDir)
            return null
        }
        val block = encodeImage(sheet)
        cleanupDir(frameDir)
        return block
    }

    private fun cleanupDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun String.escapeShell(): String = "'" + replace("'", "'\\''") + "'"

    /** Best-effort source tag: which tool (or "user") produced this path. */
    private fun detectSource(turnMessages: List<AiMessage>, path: String): String {
        for (msg in turnMessages) {
            if (msg is AiMessage.ToolCall && msg.resultRawContent.contains(path)) return msg.name
        }
        return "user"
    }

    /**
     * Write seen_images rows for everything injected this request.
     * Best-effort: a bookkeeping failure never blocks the request.
     */
    private suspend fun recordSeenImages(
        turnMessages: List<AiMessage>,
        userPaths: List<String>,
        injectedPaths: List<String>
    ) {
        try {
            val now = System.currentTimeMillis()
            val entities = injectedPaths.map { path ->
                val isVideoSheet = VIDEO_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())
                SeenImageEntity(
                    path = path,
                    mimeType = if (isVideoSheet) "video/contact-sheet" else IMAGE_URI_MIME,
                    timestamp = now,
                    conversationId = "unscoped",
                    source = detectSource(turnMessages, path)
                )
            }
            if (entities.isNotEmpty()) {
                seenImageDao.insertAll(entities)
                Log.d(TAG, "ImageAssembler: recorded ${entities.size} image(s) in seen_images")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ImageAssembler: failed to record seen_images: ${e.message}")
        }
    }

    /** User-referenced paths may be absolute device paths; resolve /storage/ to /sdcard when needed. */
    private fun resolveUserPath(rawPath: String): File {
        val file = File(rawPath)
        if (file.exists()) return file
        val storageIdx = rawPath.indexOf("/storage/emulated/0/")
        if (storageIdx >= 0) {
            val alt = File("/sdcard", rawPath.substring(storageIdx + "/storage/emulated/0/".length))
            if (alt.exists()) return alt
        }
        return file
    }
}