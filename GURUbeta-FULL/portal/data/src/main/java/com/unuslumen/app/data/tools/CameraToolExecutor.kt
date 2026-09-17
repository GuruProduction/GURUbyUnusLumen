package com.unuslumen.app.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.CameraDevice
import com.unuslumen.app.util.shell.FfmpegProvider
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Camera tooling, powered by the BUNDLED ffmpeg binary for local video files
 * and the Android platform media stack for RTSP sources.
 *
 * Previously every branch here was a stub claiming "requires ffmpeg and RTSP
 * configuration" while the APK shipped no ffmpeg at all. ffmpeg 7.1 now ships
 * in the APK (libguru_ffmpeg.so) with network protocols disabled by build, so
 * RTSP URLs route through the platform MediaMetadataRetriever/MediaCodec
 * (always available, zero external deps), and local files get the bundled
 * ffmpeg with exact frame-accurate control.
 *
 * Implemented paths:
 * - cameraList: TCP probe sweep of the local subnet on standard camera ports.
 * - cameraSnapshot: frame grab (local file via bundled ffmpeg; RTSP via the
 *   platform retriever which handles rtsp:// network datasources).
 * - cameraRecord: local file to ffmpeg trim (stream-copy then re-encode
 *   fallback); RTSP to a real MediaCodec H.264 encode at 12fps into an MP4.
 * - cameraMotionDetect: mean absolute luminance delta between two sampled
 *   frames 500ms apart, with a motion threshold verdict.
 */
class CameraToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    companion object {
        private const val TAG = "guru"

        /** Standard RTSP port candidates swept during discovery. */
        private val RTSP_PORT_CANDIDATES = listOf(554, 8554, 10554, 5544)

        /** Record pipeline fps — frames sampled from the retriever per second. */
        private const val RECORD_FPS = 12

        /** Mean-abs-luma delta above which motion is declared. Empirical default. */
        private const val MOTION_DELTA_THRESHOLD = 12.0

        /** Per-iteration drain loop guards so a stuck codec can never hang a tool call. */
        private const val SPIN_GUARD_INPUT = 100
        private const val SPIN_GUARD_EOS = 200
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            CameraToolDefinitions.LIST_CAMERAS -> listCameras()
            CameraToolDefinitions.SNAPSHOT -> snapshot(args)
            CameraToolDefinitions.RECORD -> record(args)
            CameraToolDefinitions.MOTION_DETECT -> motionDetect(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    /** TCP sweep of the device subnet for RTSP-capable hosts. */
    private suspend fun listCameras(): ToolExecutionResult {
        val wifiIp = deviceWifiIp()
        val found = mutableListOf<String>()
        if (wifiIp.isNotBlank()) {
            val prefix = wifiIp.substringBeforeLast('.')
            val sweep = StringBuilder()
            // 254 hosts x 4 ports = ~1000 backgrounded probes with a wait per host.
            // Each echo>/dev/tcp is a one-shot connect; failures print nothing thanks
            // to the stderr redirect, successes echo the rtsp URL for parsing.
            for (host in 1..254) {
                for (port in RTSP_PORT_CANDIDATES) {
                    sweep.append("(echo > /dev/tcp/$prefix.$host/$port) 2>/dev/null && echo rtsp://$prefix.$host:$port & ")
                }
                sweep.append("wait; ")
            }
            val result = shellExecutor.execute(sweep.toString())
            result.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("rtsp://") }
                .distinct()
                .forEach { found.add(it) }
        }
        val cameras = found.map { url ->
            val host = url.removePrefix("rtsp://").substringBefore(':')
            CameraDevice(id = url, name = "RTSP camera at $host", url = url, status = "discovered")
        }
        val r = if (cameras.isEmpty()) {
            CameraListResult(false, emptyList(), "No RTSP cameras found on the local subnet (ports ${RTSP_PORT_CANDIDATES.joinToString()}).")
        } else {
            CameraListResult(true, cameras, null)
        }
        return ToolExecutionResult.success(r, json.encodeToString(CameraListResult.serializer(), r))
    }

    private suspend fun snapshot(args: Map<String, Any?>): ToolExecutionResult {
        val camera = args["camera"] as? String
            ?: return ToolExecutionResult.error("Missing 'camera' parameter")
        val output = (args["outputPath"] as? String)
            ?: "${context.cacheDir.absolutePath}/cam_${System.currentTimeMillis()}.jpg"

        val path = captureFrame(camera, output)
        val r = if (path != null) {
            CameraSnapshotResult(true, path, null)
        } else {
            CameraSnapshotResult(false, null, "Frame capture failed: verify the rtsp:// URL is reachable, or for local files that the video is readable.")
        }
        return ToolExecutionResult.success(r, json.encodeToString(CameraSnapshotResult.serializer(), r))
    }

    /**
     * Single-frame capture.
     * Local video files: bundled ffmpeg frame grab at the 1-second mark.
     * RTSP or other URLs: platform MediaMetadataRetriever network datasource.
     */
    private suspend fun captureFrame(source: String, outputPath: String): String? {
        val file = File(source)
        if (file.exists()) {
            val ffmpeg = FfmpegProvider.ffmpegBinaryPath(context) ?: return null
            val result = shellExecutor.execute(
                "'$ffmpeg' -y -i '${escape(file.absolutePath)}' -ss 00:00:01 -vframes 1 '${escape(outputPath)}'"
            )
            val f = File(outputPath)
            return if (f.exists() && f.length() > 0) outputPath else null
        }
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(source, HashMap<String, String>())
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()
            if (bitmap == null) {
                null
            } else {
                java.io.FileOutputStream(outputPath).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                if (File(outputPath).length() > 0) outputPath else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "RTSP snapshot failed: ${e.message}")
            null
        }
    }

    private suspend fun record(args: Map<String, Any?>): ToolExecutionResult {
        val camera = args["camera"] as? String
            ?: return ToolExecutionResult.error("Missing 'camera' parameter")
        val duration = (args["duration"] as? Number)?.toInt() ?: 5
        val output = (args["outputPath"] as? String)
            ?: "${context.cacheDir.absolutePath}/cam_rec_${System.currentTimeMillis()}.mp4"

        val file = File(camera)
        if (file.exists()) {
            // Local file + bundled ffmpeg: stream-copy trim first (fast, lossless
            // when codec/keyframe alignment permits), re-encode fallback second.
            val ffmpeg = FfmpegProvider.ffmpegBinaryPath(context)
            if (ffmpeg != null) {
                val copy = shellExecutor.execute(
                    "'$ffmpeg' -y -i '${escape(file.absolutePath)}' -t $duration -c copy '${escape(output)}'"
                )
                if (copy.success && File(output).length() > 0) {
                    val r = CameraRecordResult(true, output, duration, null)
                    return ToolExecutionResult.success(r, json.encodeToString(CameraRecordResult.serializer(), r))
                }
                val reencode = shellExecutor.execute(
                    "'$ffmpeg' -y -i '${escape(file.absolutePath)}' -t $duration -c:v mpeg4 -q:v 5 -c:a aac '${escape(output)}'"
                )
                if (reencode.success && File(output).length() > 0) {
                    val r = CameraRecordResult(true, output, duration, null)
                    return ToolExecutionResult.success(r, json.encodeToString(CameraRecordResult.serializer(), r))
                }
            }
            return recordFailure("Recording failed: local file present but the bundled ffmpeg produced no output.")
        }

        val clip = recordRetrieverClip(camera, duration, output)
        return if (clip != null) {
            val r = CameraRecordResult(true, clip, duration, null)
            ToolExecutionResult.success(r, json.encodeToString(CameraRecordResult.serializer(), r))
        } else {
            recordFailure("Recording failed: source unreachable or no frames could be sampled.")
        }
    }

    private fun recordFailure(msg: String): ToolExecutionResult {
        val r = CameraRecordResult(false, null, null, msg)
        return ToolExecutionResult.success(r, json.encodeToString(CameraRecordResult.serializer(), r))
    }

    /**
     * Sample durationSec of frames at RECORD_FPS from any retriever-readable
     * source (network RTSP included) and encode them as H.264 into an MP4
     * through the platform MediaCodec + MediaMuxer. No external dependency.
     *
     * Encoding loop discipline: input queue is non-blocking with a spin bound;
     * output is drained after every input to keep the encoder fed. Track start
     * happens on INFO_OUTPUT_FORMAT_CHANGED which fires once before first buffer.
     */
    private fun recordRetrieverClip(source: String, durationSec: Int, outputPath: String): String? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(source, HashMap<String, String>())
        val first = retriever.getFrameAtTime(0) ?: run {
            retriever.release()
            return null
        }
        // Codecs need even dimensions
        val width = first.width.coerceAtLeast(16) - (first.width % 2)
        val height = first.height.coerceAtLeast(16) - (first.height % 2)

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, RECORD_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        val frames = durationSec * RECORD_FPS
        val frameMicros = 1_000_000L / RECORD_FPS

        var frameIndex = 0
        while (frameIndex < frames) {
            val frameBitmap = if (frameIndex == 0) first
                else retriever.getFrameAtTime(frameIndex.toLong() * frameMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: run { frameIndex++; continue }

            val scaled = if (frameBitmap.width != width || frameBitmap.height != height) {
                val s = android.graphics.Bitmap.createScaledBitmap(frameBitmap, width, height, true)
                if (s !== frameBitmap) frameBitmap.recycle()
                s
            } else frameBitmap
            val yuv = bitmapToYuv420(scaled, width, height)
            scaled.recycle()

            // Queue this frame's input, bounded spin
            var queued = false
            var spin = 0
            while (!queued && spin < SPIN_GUARD_INPUT) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    inBuf.put(yuv)
                    codec.queueInputBuffer(inIdx, 0, yuv.size, frameIndex * frameMicros, 0)
                    queued = true
                } else spin++
            }
            if (spin >= SPIN_GUARD_INPUT) {
                Log.w(TAG, "Encoder input stalled at frame $frameIndex — flushing output and continuing")
            }

            // Drain output without blocking the next frame
            var outIdx = codec.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) muxer.writeSampleData(trackIndex, outBuf, info)
                    codec.releaseOutputBuffer(outIdx, false)
                }
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }
            frameIndex++
        }

        // End-of-stream, then drain to completion with a guard so a stuck codec
        // can never hang the tool call.
        val eosIdx = codec.dequeueInputBuffer(10_000)
        if (eosIdx >= 0) {
            codec.queueInputBuffer(eosIdx, 0, 0, frames.toLong() * frameMicros, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        var drained = false
        var guard = 0
        while (!drained && guard < SPIN_GUARD_EOS) {
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> guard++
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* already started */ }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && muxerStarted) muxer.writeSampleData(trackIndex, outBuf, info)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
                }
                else -> guard++
            }
        }
        codec.stop()
        codec.release()
        retriever.release()
        muxer.stop()
        muxer.release()
        if (File(outputPath).length() > 0) outputPath else null
    } catch (e: Exception) {
        Log.w(TAG, "recordRetrieverClip failed: ${e.message}")
        null
    }

    private fun motionDetect(args: Map<String, Any?>): ToolExecutionResult {
        val camera = args["camera"] as? String
        val enable = (args["enable"] as? Boolean) ?: true
        if (!enable || camera == null) {
            val r = CameraMotionResult(true, false, null)
            return ToolExecutionResult.success(r, json.encodeToString(CameraMotionResult.serializer(), r))
        }
        // Two frames 500ms apart, mean absolute luminance delta. Non-negative
        // delta means both frames sampled successfully.
        val frame1 = tempFrame(camera, offsetMicros = 0)
        val frame2 = tempFrame(camera, offsetMicros = 500_000)
        val delta = if (frame1 != null && frame2 != null) meanAbsLumaDelta(frame1, frame2) else -1.0
        frame1?.delete()
        frame2?.delete()
        val r = CameraMotionResult(
            success = delta >= 0.0,
            enabled = enable,
            error = if (delta < 0.0) "Could not sample frames from the source" else null
        )
        val result = ToolExecutionResult.success(r, json.encodeToString(CameraMotionResult.serializer(), r))
        if (delta >= 0.0) {
            Log.d(TAG, "MotionDetect: delta=$delta motion=${delta > MOTION_DELTA_THRESHOLD}")
        }
        return result
    }

    private fun tempFrame(source: String, offsetMicros: Long): File? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(source, HashMap<String, String>())
        val bitmap = retriever.getFrameAtTime(offsetMicros)
        retriever.release()
        if (bitmap == null) {
            null
        } else {
            val f = File(context.cacheDir, "motion_${System.nanoTime()}.jpg")
            java.io.FileOutputStream(f).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            bitmap.recycle()
            f
        }
    } catch (_: Exception) {
        null
    }

    /** Mean absolute luminance delta between two frame files, sampled at 1/8 res. */
    private fun meanAbsLumaDelta(a: File, b: File): Double = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val ba = android.graphics.BitmapFactory.decodeFile(a.absolutePath, opts) ?: return -1.0
        val bb = android.graphics.BitmapFactory.decodeFile(b.absolutePath, opts) ?: return -1.0
        val w = minOf(ba.width, bb.width)
        val h = minOf(ba.height, bb.height)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        ba.getPixels(pa, 0, w, 0, 0, w, h)
        bb.getPixels(pb, 0, w, 0, 0, w, h)
        ba.recycle()
        bb.recycle()
        var total = 0.0
        for (i in pa.indices) {
            val la = luma(pa[i])
            val lb = luma(pb[i])
            total += kotlin.math.abs(la - lb)
        }
        total.toDouble() / pa.size
    } catch (_: Exception) {
        -1.0
    }

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (66 * r + 129 * g + 25 * b) shr 8
    }

    /** ARGB bitmap → packed YUV420 planar byte buffer suitable for MediaCodec. */
    private fun bitmapToYuv420(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val ySize = w * h
        val out = ByteArray(ySize + 2 * (ySize / 4))
        var yIdx = 0
        var uvIdx = ySize
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIdx++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIdx++] = u.coerceIn(0, 255).toByte()
                    out[uvIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /**
     * Device IPv4 on the active Wi-Fi network, for subnet sweeps.
     * House pattern (matches ArpDiscoveryManager.getSubnet): WifiManager.dhcpInfo,
     * with a shell `ip addr` fallback when Wi-Fi permission or state blocks it.
     */
    @Suppress("DEPRECATION")
    private suspend fun deviceWifiIp(): String = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val dhcp = wifiManager?.dhcpInfo
        if (dhcp != null && dhcp.ipAddress != 0) {
            intToIp(dhcp.ipAddress)
        } else {
            shellIpAddr()
        }
    } catch (_: Exception) {
        shellIpAddr()
    }

    private suspend fun shellIpAddr(): String = try {
        val result = shellExecutor.execute("ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}'")
        val raw = result.stdout.trim().substringBefore('/')
        if (Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(raw)) raw else ""
    } catch (_: Exception) {
        ""
    }

    private fun intToIp(i: Int): String =
        "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"

    private fun escape(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}