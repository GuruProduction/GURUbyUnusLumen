package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MediaToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        MediaToolDefinitions.SPOTIFY_STATUS -> spotifyStatus()
        MediaToolDefinitions.SPOTIFY_PLAY -> spotifyPlay()
        MediaToolDefinitions.SPOTIFY_PAUSE -> spotifyPause()
        MediaToolDefinitions.SPOTIFY_NEXT -> spotifyNext()
        MediaToolDefinitions.SPOTIFY_PREVIOUS -> spotifyPrevious()
        MediaToolDefinitions.SPOTIFY_SEARCH -> spotifySearch(args)
        MediaToolDefinitions.SPOTIFY_DEVICES -> spotifyDevices()
        MediaToolDefinitions.SPOTIFY_SET_DEVICE -> spotifySetDevice(args)
        MediaToolDefinitions.GIF_SEARCH -> gifSearch(args)
        MediaToolDefinitions.GIF_DOWNLOAD -> gifDownload(args)
        MediaToolDefinitions.MEME_SEARCH -> memeSearch(args)
        MediaToolDefinitions.MEME_CREATE -> memeCreate(args)
        MediaToolDefinitions.VIDEO_FRAME -> videoFrame(args)
        MediaToolDefinitions.VIDEO_SHEET -> videoSheet(args)
        MediaToolDefinitions.CAMERA_CAPTURE -> cameraCapture(args)
        MediaToolDefinitions.SONG_RECOGNIZE -> songRecognize()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun spotifyStatus(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo status 2>/dev/null || spotify_player status 2>/dev/null")
        val r = if (result.success) SpotifyStatusResult(success = true, status = result.stdout.trim(), error = null)
        else SpotifyStatusResult(success = false, status = null, error = "Spotify CLI not available. Install spogo or spotify_player.")
        ToolExecutionResult.success(r, json.encodeToString(SpotifyStatusResult.serializer(), r))
    }

    private suspend fun spotifyPlay(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo play 2>/dev/null || spotify_player playback play 2>/dev/null")
        val r = SpotifyResult(success = result.success, action = "play", error = if (!result.success) "Failed to resume playback: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SpotifyResult.serializer(), r))
    }

    private suspend fun spotifyPause(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo pause 2>/dev/null || spotify_player playback pause 2>/dev/null")
        val r = SpotifyResult(success = result.success, action = "pause", error = if (!result.success) "Failed to pause: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SpotifyResult.serializer(), r))
    }

    private suspend fun spotifyNext(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo next 2>/dev/null || spotify_player playback next 2>/dev/null")
        val r = SpotifyResult(success = result.success, action = "next", error = if (!result.success) "Failed to skip: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SpotifyResult.serializer(), r))
    }

    private suspend fun spotifyPrevious(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo prev 2>/dev/null || spotify_player playback previous 2>/dev/null")
        val r = SpotifyResult(success = result.success, action = "previous", error = if (!result.success) "Failed to go back: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SpotifyResult.serializer(), r))
    }

    private suspend fun spotifySearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val escapedQuery = query.replace("\"", "\\\"").replace("'", "\\'")
        val result = shellExecutor.execute("spogo search track \"$escapedQuery\" 2>/dev/null | head -n $limit")
        val r = if (result.success) {
            val tracks = result.stdout.lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(" - ", limit = 2)
                SpotifyTrack(name = parts.getOrNull(0) ?: line, artist = parts.getOrNull(1) ?: "", uri = null)
            }
            SpotifySearchResult(success = true, tracks = tracks, error = null)
        } else SpotifySearchResult(success = false, tracks = emptyList(), error = "Search failed: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(SpotifySearchResult.serializer(), r))
    }

    private suspend fun spotifyDevices(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("spogo device list 2>/dev/null")
        val r = if (result.success) {
            val devices = result.stdout.lines().filter { it.isNotBlank() }.map { SpotifyDevice(name = it.trim(), id = it.trim()) }
            SpotifyDevicesResult(success = true, devices = devices, error = null)
        } else SpotifyDevicesResult(success = false, devices = emptyList(), error = "Failed to list devices: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(SpotifyDevicesResult.serializer(), r))
    }

    private suspend fun spotifySetDevice(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val device = args["device"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'device'")
        val result = shellExecutor.execute("spogo device set \"$device\" 2>/dev/null")
        val r = SpotifyResult(success = result.success, action = "set_device", error = if (!result.success) "Failed to set device: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SpotifyResult.serializer(), r))
    }

    private suspend fun gifSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5
        val provider = args["provider"] as? String ?: "tenor"
        val source = if (provider.lowercase() == "giphy") "--source giphy" else ""
        val result = shellExecutor.execute("gifgrep search --json \"$query\" --max $limit $source 2>/dev/null")
        val r = if (result.success && result.stdout.isNotBlank()) {
            GifSearchResult(success = true, gifs = parseGifResults(result.stdout), error = null)
        } else GifSearchResult(success = false, gifs = emptyList(), error = "GIF search unavailable. Install gifgrep or provide Tenor/Giphy API key.")
        ToolExecutionResult.success(r, json.encodeToString(GifSearchResult.serializer(), r))
    }

    private suspend fun gifDownload(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val filename = args["filename"] as? String ?: "gif"
        if (!torManager.isReady.value) {
            val r = GifDownloadResult(success = false, path = null, error = "Tor is not running. Cannot download over clearnet.")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(GifDownloadResult.serializer(), r))
        }
        var conn: HttpURLConnection? = null
        try {
            val dir = java.io.File(context.filesDir, "gifs")
            dir.mkdirs()
            val file = java.io.File(dir, "${filename}_${System.currentTimeMillis()}.gif")
            val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            val r = if (file.exists() && file.length() > 0) GifDownloadResult(success = true, path = file.absolutePath, error = null)
            else GifDownloadResult(success = false, path = null, error = "Downloaded file is empty")
            ToolExecutionResult.success(r, json.encodeToString(GifDownloadResult.serializer(), r))
        } catch (e: Exception) {
            val r = GifDownloadResult(success = false, path = null, error = "Download error: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(GifDownloadResult.serializer(), r))
        } finally { conn?.disconnect() }
    }

    private suspend fun memeSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val result = shellExecutor.execute("node /data/data/com.unuslumen.app.guru.debug/files/home/openclaw/skills/meme-maker/scripts/meme.mjs search \"$query\" --json 2>/dev/null")
        val r = if (result.success) MemeSearchResult(success = true, templates = parseMemeTemplates(result.stdout), error = null)
        else MemeSearchResult(success = false, templates = emptyList(), error = "Meme search unavailable. Meme templates not configured.")
        ToolExecutionResult.success(r, json.encodeToString(MemeSearchResult.serializer(), r))
    }

    private suspend fun memeCreate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val template = args["template"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'template'")
        val topText = args["topText"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'topText'")
        val bottomText = args["bottomText"] as? String
        val dir = java.io.File(context.filesDir, "memes")
        dir.mkdirs()
        val outputFile = java.io.File(dir, "${template}_${System.currentTimeMillis()}.png")
        val textArgs = buildString { append("--text \"$topText\""); bottomText?.let { append(" --text \"$it\"") } }
        val result = shellExecutor.execute("node /data/data/com.unuslumen.app.guru.debug/files/home/openclaw/skills/meme-maker/scripts/meme.mjs render $template $textArgs --out ${outputFile.absolutePath} 2>/dev/null")
        val r = if (result.success && outputFile.exists()) MemeCreateResult(success = true, path = outputFile.absolutePath, error = null)
        else MemeCreateResult(success = false, path = null, error = "Meme creation failed: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(MemeCreateResult.serializer(), r))
    }

    /**
     * Extract a single video frame using Android's MediaMetadataRetriever — no
     * external binary needed. Accepts "HH:MM:SS", "MM:SS", or "1.5" style timestamps.
     */
    private suspend fun videoFrame(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val videoPath = args["videoPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'videoPath'")
        val timestamp = args["timestamp"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'timestamp'")
        val outputName = args["outputName"] as? String ?: "frame"
        val videoFile = java.io.File(videoPath)
        if (!videoFile.exists()) {
            val r = VideoFrameResult(success = false, path = null, error = "Video file not found: $videoPath")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
        }
        val timeUs = parseTimestampToMicros(timestamp)
        if (timeUs == null) {
            val r = VideoFrameResult(
                success = false, path = null,
                error = "Invalid timestamp: '$timestamp'. Use '00:01:30', 'MM:SS', or seconds like '1.5'."
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
        }
        val retriever = android.media.MediaMetadataRetriever()
        val r: VideoFrameResult = try {
            retriever.setDataSource(videoPath)
            val bitmap = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap == null) {
                VideoFrameResult(success = false, path = null, error = "No frame at $timestamp. Video may be shorter than the requested time or the codec is unsupported.")
            } else {
                val dir = java.io.File(context.filesDir, "frames")
                dir.mkdirs()
                val outputFile = java.io.File(dir, "${outputName}_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(outputFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                if (outputFile.exists() && outputFile.length() > 0) {
                    VideoFrameResult(success = true, path = outputFile.absolutePath, error = null)
                } else {
                    VideoFrameResult(success = false, path = null, error = "Frame extraction produced an empty file")
                }
            }
        } catch (e: Exception) {
            VideoFrameResult(success = false, path = null, error = "Frame extraction failed: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
    }

    /**
     * Contact sheet: N frames at evenly-spaced timestamps via MediaMetadataRetriever,
     * composited into a grid on a Canvas. Pure Android APIs, no external binary.
     */
    private suspend fun videoSheet(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val videoPath = args["videoPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'videoPath'")
        val requestedFrames = (args["frames"] as? Number)?.toInt() ?: 9
        val requestedColumns = (args["columns"] as? Number)?.toInt() ?: 3
        val outputName = args["outputName"] as? String ?: "sheet"
        val videoFile = java.io.File(videoPath)
        if (!videoFile.exists()) {
            val r = VideoFrameResult(success = false, path = null, error = "Video file not found: $videoPath")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
        }
        val frames = requestedFrames.coerceIn(1, 16)
        val columnsSafe = requestedColumns.coerceIn(1, 6)
        val retriever = android.media.MediaMetadataRetriever()
        val tiles = mutableListOf<android.graphics.Bitmap>()
        try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs == null || durationMs <= 0) {
                val r = VideoFrameResult(success = false, path = null, error = "Could not read video duration. Format may be unsupported.")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
            }
            for (i in 0 until frames) {
                val timeUs = durationMs * 1000L * i / frames
                val bmp = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) tiles.add(bmp)
            }
        } catch (e: Exception) {
            tiles.forEach { try { it.recycle() } catch (_: Exception) {} }
            tiles.clear()
            val r = VideoFrameResult(success = false, path = null, error = "Contact sheet creation failed: ${e.message}")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (tiles.isEmpty()) {
            val r = VideoFrameResult(success = false, path = null, error = "Could not extract any frames — codec unsupported on this device.")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
        }
        val frameW = tiles.first().width.coerceAtMost(400).coerceAtLeast(1)
        val frameH = (frameW.toLong() * tiles.first().height / maxOf(tiles.first().width, 1)).toInt().coerceAtLeast(1)
        val rows = (tiles.size + columnsSafe - 1) / columnsSafe
        val sheet = android.graphics.Bitmap.createBitmap(frameW * columnsSafe, frameH * rows, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(android.graphics.Color.BLACK)
        tiles.forEachIndexed { index, bmp ->
            val scaled = if (frameW != bmp.width || frameH != bmp.height) {
                android.graphics.Bitmap.createScaledBitmap(bmp, frameW, frameH, true)
            } else bmp
            val col = index % columnsSafe
            val row = index / columnsSafe
            canvas.drawBitmap(scaled, (col * frameW).toFloat(), (row * frameH).toFloat(), null)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
        tiles.clear()
        val dir = java.io.File(context.filesDir, "frames")
        dir.mkdirs()
        val outputFile = java.io.File(dir, "${outputName}_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(outputFile).use { out ->
            sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        }
        sheet.recycle()
        val ok = outputFile.exists() && outputFile.length() > 0
        val r = VideoFrameResult(
            success = ok,
            path = if (ok) outputFile.absolutePath else null,
            error = if (ok) null else "Contact sheet creation failed — output file empty"
        )
        ToolExecutionResult.success(r, json.encodeToString(VideoFrameResult.serializer(), r))
    }

    /**
     * Parse a timestamp into microseconds. Accepts "HH:MM:SS", "MM:SS",
     * and plain seconds ("1.5" / "90").
     */
    private fun parseTimestampToMicros(timestamp: String): Long? {
        val trimmed = timestamp.trim()
        // Plain seconds: "1.5", "90", "0"
        trimmed.toDoubleOrNull()?.let { secs -> return (secs * 1_000_000).toLong() }
        // Clock formats: HH:MM:SS(.frac) or MM:SS(.frac)
        val parts = trimmed.split(":")
        if (parts.size < 2 || parts.size > 3) return null
        val secondsPart = parts.last()
        val secondsWhole = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val frac = if (secondsPart.contains('.')) {
            val fracRaw = secondsPart.substringAfter('.')
            if (fracRaw.isEmpty()) 0.0 else ("0.$fracRaw".toDoubleOrNull() ?: 0.0)
        } else 0.0
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) (parts[0].toLongOrNull() ?: return null) else 0L
        val totalSeconds = hours * 3600.0 + minutes * 60.0 + secondsWhole + frac
        if (totalSeconds < 0) return null
        return (totalSeconds * 1_000_000).toLong()
    }

    private suspend fun cameraCapture(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val camera = args["camera"] as? String ?: "back"
        val filename = args["filename"] as? String ?: "photo"
        val dir = java.io.File(context.filesDir, "photos")
        dir.mkdirs()
        val outputFile = java.io.File(dir, "${filename}_${System.currentTimeMillis()}.jpg")
        val cameraIndex = if (camera == "front") "1" else "0"
        val screenshotResult = shellExecutor.execute("screencap -p -c $cameraIndex \"${outputFile.absolutePath}\" 2>/dev/null")
        if (screenshotResult.success && outputFile.exists() && outputFile.length() > 0) {
            val r = CameraResult(success = true, path = outputFile.absolutePath, error = null)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(CameraResult.serializer(), r))
        }
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${filename}_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/guru")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(cameraIntent)
                val r = CameraResult(success = true, path = uri.toString(), error = "Camera app opened — user must take photo manually. Image will be saved to Pictures/guru.")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(CameraResult.serializer(), r))
            }
        } catch (e: Exception) {
            try {
                val simpleIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(simpleIntent)
                val r = CameraResult(success = true, path = null, error = "Camera app opened. Photo not auto-captured — user must take photo manually.")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(CameraResult.serializer(), r))
            } catch (e2: Exception) {
                val r = CameraResult(success = false, path = null, error = "Camera capture failed: ${e2.message}")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(CameraResult.serializer(), r))
            }
        }
        val r = CameraResult(success = false, path = null, error = "Camera capture failed. No camera method available.")
        ToolExecutionResult.success(r, json.encodeToString(CameraResult.serializer(), r))
    }

    private suspend fun songRecognize(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = SongResult(success = false, title = null, artist = null, album = null, error = "Song recognition requires ACRCloud API key. Configure in settings.")
        ToolExecutionResult.success(r, json.encodeToString(SongResult.serializer(), r))
    }

    private fun parseGifResults(jsonOutput: String): List<GifInfo> {
        return jsonOutput.lines().filter { it.contains("\"url\"") || it.contains("\"preview\"") }.take(10).mapIndexed { index, _ ->
            GifInfo(id = "gif_$index", url = "", previewUrl = "", title = "GIF $index")
        }
    }

    private fun parseMemeTemplates(jsonOutput: String): List<MemeTemplate> {
        return jsonOutput.lines().filter { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("}") }.take(20).mapIndexed { _, line ->
            MemeTemplate(name = line.trim().removeSurrounding("\""), description = "Meme template")
        }
    }
}