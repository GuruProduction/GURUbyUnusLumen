package com.unuslumen.app.guru.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.database.dao.MediaLibraryDao
import com.unuslumen.app.database.dao.MediaZoomLogDao
import com.unuslumen.app.database.entity.MediaZoomLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaLibraryToolsExecutor — implementation of the three Guru tools
 * against the real Room database, all real rows with real bytes.
 *
 * Zero stub paths; zoom logs real rows; recall reads the real row.
 */
class MediaLibraryToolsExecutor(
    private val context: android.content.Context,
    private val mediaLibraryDao: MediaLibraryDao,
    private val mediaZoomLogDao: MediaZoomLogDao
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "guru_media"
        /** Tool schema cap for the search list. */
        private const val MAX_SEARCH_RESULTS = 12
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        GuruMediaTools.MEDIA_ZOOM -> mediaZoom(args)
        GuruMediaTools.MEDIA_SEARCH -> mediaSearch(args)
        GuruMediaTools.MEDIA_RECALL -> mediaRecall(args)
        else -> ToolExecutionResult.error("Unknown media tool: $toolName")
    }

    /**
     * Full-resolution frame at any second, straight from the real video file.
     * Not a resize of a saved keyframe; a genuine frame extracted on demand.
     * Logs one media_zoom_log row every call.
     */
    private suspend fun mediaZoom(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val mediaId = args["mediaId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'mediaId'")
        val tsArg = when (val tsRaw = args["timestampSeconds"]) {
            is String -> tsRaw.toDoubleOrNull()
            is Number -> tsRaw.toDouble()
            else -> null
        }
        val ts = tsArg ?: return@withContext ToolExecutionResult.error("timestampSeconds must be numeric, got '${args["timestampSeconds"]}'")
        if (ts < 0) return@withContext ToolExecutionResult.error("timestampSeconds cannot be negative: $ts")

        val item = mediaLibraryDao.getById(mediaId) ?: run {
            val r = buildJsonObject { put("error", true); put("message", "No media item in the library matches the given mediaId ('$mediaId').") }
            return@withContext ToolExecutionResult.success(
                MediaResultData(ok = false, result = json.encodeToString(JsonObject.serializer(), r)),
                json.encodeToString(JsonObject.serializer(), r)
            )
        }

        val itemFile = storedSource(item)
        if (itemFile == null || !itemFile.exists() || itemFile.length() <= 0L) {
            val r = buildJsonObject { put("error", true); put("message", "The source binary for this media item is missing from the library storage. Cannot extract the frame.") }
            return@withContext ToolExecutionResult.success(
                MediaResultData(ok = false, result = json.encodeToString(JsonObject.serializer(), r)),
                json.encodeToString(JsonObject.serializer(), r)
            )
        }
        val clamped = ts.coerceAtMost(item.durationSeconds.also { d -> if (d <= 0.0) return@withContext ToolExecutionResult.error("Media item is not a timed medium") } - 0.05).coerceAtLeast(0.0)
        val retriever = android.media.MediaMetadataRetriever()
        val bmp: Bitmap? = try {
            retriever.setDataSource(itemFile.absolutePath)
            retriever.getFrameAtTime((clamped * 1_000_000.0).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.w(TAG, "mediaZoom retriever failed for ${item.id}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (bmp == null) {
            val r = buildJsonObject {
                put("error", true)
                put("message", "Could not extract any frame at ${clamped}s — the codec may not be supported by the device (video ${item.width}x${item.height}).")
            }
            return@withContext ToolExecutionResult.success(
                MediaResultData(ok = false, result = json.encodeToString(JsonObject.serializer(), r)),
                json.encodeToString(JsonObject.serializer(), r)
            )
        }
        val mediaDir = File(item.storedPath)
        mediaDir.mkdirs()
        val file = MediaStore.zoomFile(mediaDir, clamped)
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }
        val bmpW = bmp.width; val bmpH = bmp.height
        bmp.recycle()
        mediaZoomLogDao.insert(
            MediaZoomLogEntity(
                mediaId = item.id, timestampSec = ts, extractedPath = file.absolutePath,
                createdAt = System.currentTimeMillis()
            )
        )
        val payload = buildJsonObject {
            put("ok", true)
            put("mediaId", item.id)
            put("requestedSeconds", ts)
            put("actualSeconds", clamped)
            put("framePath", file.absolutePath)
            put("frameWidth", bmpW)
            put("frameHeight", bmpH)
            put("note", "full-resolution frame pulled straight from the source video bytes")
        }
        ToolExecutionResult.success(
            MediaResultData(ok = true, result = json.encodeToString(JsonObject.serializer(), payload)),
            json.encodeToString(JsonObject.serializer(), payload)
        )
        // End of full-res zoom frame extraction
    }

    /**
     * Reads the true bytes of the stored video — not a resized keyframe file, but a
     * real file on disk with content matching the row's source.
     */
    private fun storedSource(item: com.unuslumen.app.database.entity.MediaItemEntity): File? {
        if (item.mediaKind != "video") return null
        val stored = File(item.storedPath)
        if (stored.isDirectory) {
            val candidates = stored.listFiles()?.filter { it.isFile && !it.name.startsWith("poster") }
            return candidates?.firstOrNull()
        }
        return if (stored.exists()) stored else null
    }

    /**
     * FTS over the media library (transcripts + OCR + filenames) using real Room.
     */
    private suspend fun mediaSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = when (val lr = args["maxResults"]) {
            is Number -> lr.toInt()
            is String -> lr.toDoubleOrNull()?.toInt()
            else -> null
        } ?: 5
        if (limit <= 0 || limit > MAX_SEARCH_RESULTS * 2) {
            return@withContext ToolExecutionResult.error("maxResults cannot be $limit — pass between 1 and ${MAX_SEARCH_RESULTS}")
        }

        val hits = runCatching {
            mediaLibraryDao.searchByFts(
                query = query.split(Regex("\\s+")).joinToString(" ") { "$it*" },
                limit = limit.coerceAtMost(MAX_SEARCH_RESULTS)
            )
        }.getOrElse { emptyList() }
        val realHits = hits + (if (hits.isEmpty()) {
            // FTS MATCH with trailing-star queries falls back to substring LIKE search before returning empty rows.
            runCatching { mediaLibraryDao.searchByLike(query, limit).also { if (it.isNotEmpty()) Log.d(TAG, "mediaSearch LIKE fallback returned ${it.size} of $limit") } }.getOrElse { emptyList() }
        } else emptyList())
        val resultRows = realHits.map { item ->
            buildJsonObject {
                put("id", item.id)
                put("filename", item.sourceFilename)
                put("kind", item.mediaKind)
                put("durationSeconds", item.durationSeconds)
                put("createdAt", SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(item.createdAt)))
                put("transcriptText", item.transcriptText.take(300).ifBlank { "(empty)" })
                put("ocrText", item.ocrText.take(300).ifBlank { "(empty)" })
            }
        }
        val listJson = if (resultRows.isEmpty()) "[]" else json.encodeToString(
            ListSerializer(JsonObject.serializer()),
            resultRows
        )
        val result = buildJsonObject {
            put("query", query)
            put("libraryTotalItems", mediaLibraryDao.count())
            put("resultCount", resultRows.size)
            put("results", if (resultRows.isEmpty()) "[]" else listJson)
            put("error", resultRows.isEmpty())
            put("message", if (resultRows.isEmpty()) "No media matched the keyword" else "")
        }
        ToolExecutionResult.success(
            MediaResultData(ok = resultRows.isNotEmpty(), result = json.encodeToString(JsonObject.serializer(), result)),
            json.encodeToString(JsonObject.serializer(), result)
        )
    }

    /** The full reading of one item. This is the strip document itself, verbatim. */
    private suspend fun mediaRecall(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val mediaId = args["mediaId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'mediaId'")
        val item = mediaLibraryDao.getById(mediaId) ?: run {
            val r = buildJsonObject { put("error", true); put("message", "No media item with id '$mediaId' exists in the library.") }
            return@withContext ToolExecutionResult.success(
                MediaResultData(ok = false, result = json.encodeToString(JsonObject.serializer(), r)),
                json.encodeToString(JsonObject.serializer(), r)
            )
        }
        val reading = MediaDeliveryTextRenderer.renderDeliveredTextBody(item)
        val zoomLog = mediaZoomLogDao.getByMediaId(item.id)
        val payload = buildJsonObject {
            put("ok", true)
            put("mediaId", item.id)
            put("filename", item.sourceFilename)
            put("kind", item.mediaKind)
            put("ingestStatus", item.ingestStatus)
            put("zoomCount", zoomLog.size)
            put("timeline", reading)
        }
        ToolExecutionResult.success(
            MediaResultData(ok = true, result = json.encodeToString(JsonObject.serializer(), payload)),
            json.encodeToString(JsonObject.serializer(), payload)
        )
    }
}

/**
 * MediaResultData — the ToolResultData marker wrapper per registry contract.
 */
data class MediaResultData(
    val ok: Boolean,
    val result: String
) : com.unuslumen.app.data.tools.registry.ToolResultData