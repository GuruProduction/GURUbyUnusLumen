// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.unuslumen.app.data.tools.SpeechRecognition
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * The camera and audio domain: video from the device camera, the torch, the audio
 * route, and a continuous listen session.
 *
 * THE TWO EXISTING TOOLS THIS DOMAIN DOES NOT DUPLICATE. cameraCapture and
 * transcribeSpeech already exist and are registered. Both were given their grant
 * check and their honesty fixes in place, in MediaToolExecutor and
 * VoiceToolExecutor, because a second photo tool and a second transcription tool
 * would be two more places for the same refusal to drift.
 *
 * WHAT IS DELIBERATELY MISSING. There is no cameraPhoto here: cameraCapture is the
 * photo tool. There is no second transcribe: transcribeSpeech is the one-shot
 * transcription, and listenStart/listenStop are the long-running session, which is a
 * different capability with a different lifetime.
 *
 * EVERY TOOL IN THIS FILE NEEDS A REAL HANDSET TO PROVE. A camera, a flash unit, an
 * audio device and a microphone are all hardware, and nothing here has been run on
 * one. The code is written so the honest answer is produced when they are absent or
 * when a switch is refused, but only Steven's own phone can show that the working
 * paths work.
 */

@Serializable
data class CameraVideoResult(
    val success: Boolean,
    val videoPath: String? = null,
    /** Duration read back from the finished file, not the requested seconds. */
    val durationSeconds: Double? = null,
    val requestedSeconds: Int = 0,
    val sizeBytes: Long? = null,
    /** True only when an audio track was found in the recorded file. */
    val hasAudio: Boolean = false,
    val cameraId: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class CameraTorchResult(
    val success: Boolean,
    val on: Boolean = false,
    val cameraId: String? = null,
    /** True only when the platform's own callback confirmed the new state. */
    val confirmed: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AudioRouteResult(
    val success: Boolean,
    val requestedRoute: String,
    /** The route read back after the switch, as the platform reports it. */
    val activeRoute: String,
    val confirmed: Boolean,
    val availableRoutes: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ListenStartResult(
    val success: Boolean,
    val listening: Boolean,
    val startedAtMillis: Long = 0,
    val maxSeconds: Int = 0,
    val note: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class ListenStopResult(
    val success: Boolean,
    val transcript: String? = null,
    val confidence: Float? = null,
    val capturedSeconds: Double? = null,
    val audioFound: Boolean = false,
    val note: String = "",
    val error: String? = null,
) : ToolResultData

/**
 * The camera and audio tools, all of which need a real handset to prove.
 *
 * Every definition names the permission that actually gates it. cameraVideo needs
 * the camera; it uses the microphone only when that grant is already held, and the
 * result reports whether an audio track was really written rather than whether one
 * was asked for.
 */
object CameraAudioDomainDefinitions : ToolSetRegistration {
    const val CAMERA_VIDEO = "cameraVideo"
    const val CAMERA_TORCH = "cameraTorch"
    const val AUDIO_ROUTE = "audioRoute"
    const val LISTEN_START = "listenStart"
    const val LISTEN_STOP = "listenStop"

    override val definitions = listOf(
        ToolDefinition(
            name = CAMERA_VIDEO,
            description = "Record a video from the device camera for a fixed number of seconds and " +
                "return the file path, its real byte size, the duration read back from the finished " +
                "file, and whether an audio track was actually written. Audio is captured only when " +
                "the Microphone grant is already held, so a silent clip is never mistaken for a broken " +
                "microphone. Needs the Camera grant.",
            category = "camera",
            parameters = listOf(
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long to record, 1 to 60 seconds. Default 10."
                ),
                ToolParameter(
                    "camera", ToolParameterType.String, false,
                    "'back' or 'front'. Default 'back'."
                ),
                ToolParameter(
                    "outputPath", ToolParameterType.String, false,
                    "Absolute path for the MP4. Defaults to a file in the app cache."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CAMERA.permission),
        ),
        ToolDefinition(
            name = CAMERA_TORCH,
            description = "Turn the camera flash on or off as a torch and report whether the platform " +
                "confirmed the new state through its torch callback. This is the torch, not the " +
                "camera: no image is captured. Use it to light a room or read a label. Needs the " +
                "Camera grant and a device whose flash unit is exposed as a camera torch.",
            category = "camera",
            parameters = listOf(
                ToolParameter(
                    "on", ToolParameterType.Boolean, true,
                    "true to light the torch, false to extinguish it."
                ),
                ToolParameter(
                    "camera", ToolParameterType.String, false,
                    "'back' or 'front'. Default 'back'; most phones expose the torch on the back camera."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CAMERA.permission),
        ),
        ToolDefinition(
            name = AUDIO_ROUTE,
            description = "Read the audio route in force, or switch the communication route to the " +
                "speaker, the earpiece, a connected Bluetooth headset or a wired headset, then read it " +
                "back and report whether it really changed. Route switching applies to the " +
                "communication path, so media playing through another output may not follow it. " +
                "Requires no dialog: Modify audio settings is held from install.",
            category = "audio",
            parameters = listOf(
                ToolParameter(
                    "route", ToolParameterType.String, false,
                    "speaker, earpiece, bluetooth or headset. Omit to read the current route only.",
                    enumValues = listOf("speaker", "earpiece", "bluetooth", "headset"),
                ),
            ),
            permissions = listOf(TeamThreePermissions.MODIFY_AUDIO_SETTINGS.permission),
        ),
        ToolDefinition(
            name = LISTEN_START,
            description = "Start a continuous listen session: the microphone stays open and audio is " +
                "kept until listenStop is called, then transcribed in one pass. This runs inside the " +
                "GURU process and ends early if the process is killed, because it is a listening " +
                "session rather than a foreground service; the result says so. Needs the Microphone " +
                "grant.",
            category = "audio",
            parameters = listOf(
                ToolParameter(
                    "maxSeconds", ToolParameterType.Integer, false,
                    "Hard cap on the session, 5 to 300 seconds. Default 120."
                ),
            ),
            permissions = listOf(TeamThreePermissions.RECORD_AUDIO.permission),
        ),
        ToolDefinition(
            name = LISTEN_STOP,
            description = "End the continuous listen session and transcribe everything it captured, " +
                "returning the text and the real captured duration. Returns an honest empty when the " +
                "session held silence or no speech. Needs the Microphone grant.",
            category = "audio",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.RECORD_AUDIO.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = CameraAudioDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The continuous listen session, in one process-scoped object.
 *
 * WHY NOT A FOREGROUND SERVICE. A microphone foreground service needs a service
 * component and a manifest entry in the app shell, which is a file this batch holds
 * shared. Rather than half-add one, the session is honestly process-scoped and every
 * result says so. That is a real limit of the capability, stated where the model
 * reads it rather than buried in a commit message.
 *
 * WHY RAW PCM. The bundled recogniser takes exactly 16 kHz mono 16-bit WAV, and a
 * live session has no container to carry those facts. Keeping raw PCM and writing
 * the WAV header at stop time means the format is exact by construction instead of
 * decoded and guessed at afterwards.
 */
object ContinuousListen {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /** What a stopped session hands back: the file, when it began, and why it failed. */
    data class Stopped(val pcm: File?, val startedAtMillis: Long, val error: String?)

    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var target: File? = null
    private val stopped = AtomicBoolean(true)

    @Volatile
    var startedAtMillis: Long = 0
        private set

    @Volatile
    private var maxSeconds: Int = 0

    fun isListening(): Boolean = synchronized(lock) { recorder != null && !stopped.get() }

    /**
     * Begin capturing to a raw PCM file. Returns null on success or an error string.
     *
     * The recorder is built with the platform builder rather than the deprecated
     * constructor, and the buffer is sized from the real minimum for this format so a
     * device with a large hardware buffer does not overrun.
     */
    fun start(context: Context, maxSeconds: Int): String? = synchronized(lock) {
        if (recorder != null) return "A listen session is already running; stop it first."

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) return "This device cannot open a 16 kHz mono microphone stream."

        val pcm = File(context.cacheDir, "listen_${System.currentTimeMillis()}.pcm")
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (e: Exception) {
            pcm.delete()
            return "The microphone could not be opened: ${e.message}"
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            pcm.delete()
            return "The microphone opened but did not initialise, which is a device or audio-server " +
                "fault rather than a grant."
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            pcm.delete()
            return "Recording could not start: ${e.message}"
        }

        recorder = record
        target = pcm
        this.maxSeconds = maxSeconds
        startedAtMillis = System.currentTimeMillis()
        stopped.set(false)

        val sessionStart = startedAtMillis
        val sessionCap = maxSeconds
        val worker = Thread({
            val buffer = ByteArray(8192)
            try {
                java.io.FileOutputStream(pcm, true).use { out ->
                    while (!stopped.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                        } else if (read < 0) {
                            break
                        }
                        if ((System.currentTimeMillis() - sessionStart) / 1000.0 >= sessionCap) break
                    }
                }
            } catch (_: Exception) {
                // A read failure mid-session leaves a shorter file, which stop()
                // reports honestly as the duration it really holds.
            }
        }, "guru-continuous-listen")
        worker.isDaemon = true
        thread = worker
        worker.start()
        return null
    }

    /** Stop capturing and hand back the raw PCM file with the session's start time. */
    fun stop(): Stopped = synchronized(lock) {
        val record = recorder
        val pcm = target
        val start = startedAtMillis
        stopped.set(true)

        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        target = null
        startedAtMillis = 0
        // The worker notices the flag between reads; joining here means the file is
        // complete before it is read, rather than racing the last buffer.
        thread?.let { worker -> runCatching { worker.join(2_000) } }
        thread = null

        if (pcm == null || !pcm.exists() || pcm.length() < 100L) {
            pcm?.delete()
            return Stopped(null, start, "The session captured no audio, so there is nothing to transcribe.")
        }
        return Stopped(pcm, start, null)
    }

    /** Wrap the raw 16 kHz mono PCM in a WAV header, which is what the recogniser demands. */
    fun toWav(pcm: File, wav: File): String? {
        val dataLen = pcm.length().toInt()
        return try {
            RandomAccessFile(wav, "rw").use { raf ->
                raf.setLength(0)
                raf.writeBytes("RIFF")
                raf.writeIntLe(36 + dataLen)
                raf.writeBytes("WAVE")
                raf.writeBytes("fmt ")
                raf.writeIntLe(16)
                raf.writeShortLe(1)                 // PCM
                raf.writeShortLe(1)                 // mono
                raf.writeIntLe(SAMPLE_RATE)
                raf.writeIntLe(SAMPLE_RATE * 2)     // byte rate
                raf.writeShortLe(2)                 // block align
                raf.writeShortLe(16)                // bits per sample
                raf.writeBytes("data")
                raf.writeIntLe(dataLen)
                // copyTo needs an OutputStream and a RandomAccessFile is not one. The
                // FileOutputStream wraps the same descriptor, so the bytes land at the
                // exact offset the header finished on rather than starting a new file.
                pcm.inputStream().use { input ->
                    java.io.FileOutputStream(raf.fd).use { out -> input.copyTo(out) }
                }
            }
            if (wav.exists() && wav.length() > 0) null else "The WAV writer produced an empty file."
        } catch (e: Exception) {
            wav.delete()
            "The captured audio could not be wrapped as a WAV: ${e.message}"
        }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}

/**
 * The executor behind cameraVideo, cameraTorch, audioRoute and the listen pair.
 *
 * Each tool checks its grant first and returns the refusal verbatim. A hardware
 * absence is reported as the device, because no grant can fix it, and the two checks
 * are ordered so a sensor-less phone is never told to grant something.
 */
class CameraAudioDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val cameraManager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        CameraAudioDomainDefinitions.CAMERA_VIDEO -> cameraVideo(args)
        CameraAudioDomainDefinitions.CAMERA_TORCH -> cameraTorch(args)
        CameraAudioDomainDefinitions.AUDIO_ROUTE -> audioRoute(args)
        CameraAudioDomainDefinitions.LISTEN_START -> listenStart(args)
        CameraAudioDomainDefinitions.LISTEN_STOP -> listenStop()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    // ------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------

    private suspend fun cameraVideo(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.CAMERA)?.let { return it }

        PermissionGrants.guardHardware(
            present = PermissionGrants.hasCamera(context),
            label = "camera",
            why = "GURU can record a video",
        )?.let { return it }

        val manager = cameraManager
            ?: return videoFailure("This device has no CameraManager service, so no video can be recorded.")

        val durationSeconds = ((args["durationSeconds"] as? Number)?.toInt() ?: 10).coerceIn(1, 60)
        val facingFront = (args["camera"] as? String)?.equals("front", ignoreCase = true) == true
        val outputFile = File(
            (args["outputPath"] as? String)
                ?: File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4").absolutePath
        )

        val cameraId = pickCameraId(manager, facingFront)
            ?: return videoFailure("No ${if (facingFront) "front" else "back"} camera exists on this device.")

        // The microphone is used only when it is already granted. Asking for it here
        // would turn a video request into a permission prompt, and a silent video is a
        // usable answer where an interrupted one is not.
        val withAudio = PermissionGrants.check(context, TeamThreePermissions.RECORD_AUDIO).allowed
        val size = preferredVideoSize(manager, cameraId)

        return recordClip(manager, cameraId, size, outputFile, durationSeconds, withAudio)
    }

    private fun videoFailure(message: String): ToolExecutionResult {
        val r = CameraVideoResult(success = false, error = message)
        return ToolExecutionResult.success(r, json.encodeToString(CameraVideoResult.serializer(), r))
    }

    private fun pickCameraId(manager: CameraManager, front: Boolean): String? {
        val ids = runCatching { manager.cameraIdList }.getOrNull().orEmpty()
        var fallback: String? = null
        for (id in ids) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            if (isFront == front) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun preferredVideoSize(manager: CameraManager, cameraId: String): Size {
        val map = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() as? StreamConfigurationMap

        val sizes = runCatching { map?.getOutputSizes(MediaRecorder::class.java) }.getOrNull().orEmpty()
        // 1080p is the largest size every H.264 encoder handles comfortably; asking
        // for more risks a configure failure on a device whose encoder is limited.
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }

    /**
     * One camera2 + MediaRecorder recording.
     *
     * Everything platform-facing runs on a dedicated HandlerThread because the
     * camera callbacks are delivered there and blocking the main thread would hold
     * the session open. The recorder's stop is guarded because stop() after a failed
     * start throws, and the finished file is probed for a real audio track so
     * [CameraVideoResult.hasAudio] states what was written rather than what was asked
     * for.
     */
    private suspend fun recordClip(
        manager: CameraManager,
        cameraId: String,
        size: Size,
        outputFile: File,
        durationSeconds: Int,
        withAudio: Boolean,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val loop = HandlerThread("guru-camera-video").apply { start() }
        val handler = Handler(loop.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null

        try {
            recorder = configureRecorder(size, outputFile, withAudio)
            // getSurface() is only valid after the output file and before prepare(), so
            // the recorder is configured in two steps: this call, then prepare() once the
            // surface has been taken. Preparing first returns a surface the camera cannot
            // target and the encode silently produces nothing.
            val surface = recorder.surface
            recorder.prepare()

            val deviceDeferred = CompletableDeferred<CameraDevice?>()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    if (!deviceDeferred.isCompleted) deviceDeferred.complete(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    if (!deviceDeferred.isCompleted) deviceDeferred.complete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    if (!deviceDeferred.isCompleted) deviceDeferred.complete(null)
                }
            }, handler)

            val opened = withTimeoutOrNull(5_000L) { deviceDeferred.await() }
                ?: return@withContext videoFailure(
                    "The camera did not open within five seconds, so no video was recorded. Another app " +
                        "holding the camera or a device-policy block produces the same delay."
                )

            val sessionDeferred = CompletableDeferred<CameraCaptureSession?>()
            opened.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        if (!sessionDeferred.isCompleted) sessionDeferred.complete(configured)
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        if (!sessionDeferred.isCompleted) sessionDeferred.complete(null)
                    }
                },
                handler,
            )

            val configured = withTimeoutOrNull(5_000L) { sessionDeferred.await() }
                ?: return@withContext videoFailure(
                    "The camera could not be configured for video, which usually means another app holds it."
                )

            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                .apply { addTarget(surface) }
                .build()
            configured.setRepeatingRequest(request, null, handler)

            recorder.start()
            delay(durationSeconds * 1000L)

            runCatching { configured.stopRepeating() }
            var stopError: String? = null
            try {
                recorder.stop()
            } catch (e: Exception) {
                stopError = e.message
            }

            val sizeBytes = if (outputFile.exists()) outputFile.length() else 0L
            if (sizeBytes <= 0L) {
                return@withContext videoFailure(
                    stopError?.let { "The encoder produced no bytes: $it" }
                        ?: "The encoder produced no bytes, so no video file was written."
                )
            }

            val r = CameraVideoResult(
                success = true,
                videoPath = outputFile.absolutePath,
                durationSeconds = probeDurationSeconds(outputFile),
                requestedSeconds = durationSeconds,
                sizeBytes = sizeBytes,
                hasAudio = probeHasAudioTrack(outputFile),
                cameraId = cameraId,
            )
            ToolExecutionResult.success(r, json.encodeToString(CameraVideoResult.serializer(), r))
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.CAMERA))
        } catch (e: Exception) {
            videoFailure("Recording failed: ${e.message}")
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { recorder?.release() }
            loop.quitSafely()
        }
    }

    /**
     * Configure a recorder without preparing it.
     *
     * The documented order is sources, output format, encoders, output file. Preparing
     * is left to the caller because `getSurface()` is only meaningful between the output
     * file being set and prepare() being called, and the camera needs that surface.
     */
    private fun configureRecorder(size: Size, outputFile: File, withAudio: Boolean): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (withAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
        }
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(size.width, size.height)
        recorder.setVideoFrameRate(30)
        recorder.setVideoEncodingBitRate(8_000_000)
        recorder.setOutputFile(outputFile.absolutePath)
        return recorder
    }

    /** The finished file's real duration, or null when the container cannot report it. */
    private fun probeDurationSeconds(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { it / 1000.0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Whether the finished file really carries an audio track. */
    private fun probeHasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------
    // Torch
    // ------------------------------------------------------------------

    private suspend fun cameraTorch(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.CAMERA)?.let { return it }

        val manager = cameraManager ?: run {
            val r = CameraTorchResult(
                success = false,
                error = "This device has no CameraManager service, so it has no torch.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(CameraTorchResult.serializer(), r))
        }

        val on = (args["on"] as? Boolean) ?: when ((args["on"] as? String)?.lowercase()) {
            "true", "on", "1" -> true
            "false", "off", "0" -> false
            else -> null
        } ?: run {
            val r = CameraTorchResult(
                success = false,
                error = "This call needs 'on' as true or false to say which way the torch should go.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(CameraTorchResult.serializer(), r))
        }

        val front = (args["camera"] as? String)?.equals("front", ignoreCase = true) == true
        val torchCamera = torchCameraId(manager, front) ?: run {
            val r = CameraTorchResult(
                success = false,
                note = "No camera on this device reports a flash unit, so the torch cannot be lit here.",
                error = "This device has no torch.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(CameraTorchResult.serializer(), r))
        }

        return try {
            manager.setTorchMode(torchCamera, on)
            val confirmed = awaitTorchState(manager, torchCamera, on)
            val r = CameraTorchResult(
                success = true,
                on = on,
                cameraId = torchCamera,
                confirmed = confirmed,
                note = if (confirmed) {
                    "The platform confirmed the torch is ${if (on) "on" else "off"}."
                } else {
                    "The torch switch was requested but the platform did not report the new state " +
                        "within a second; another camera client or a thermal limit can hold it."
                },
            )
            ToolExecutionResult.success(r, json.encodeToString(CameraTorchResult.serializer(), r))
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.CAMERA))
        } catch (e: Exception) {
            val r = CameraTorchResult(
                success = false,
                cameraId = torchCamera,
                error = "The torch could not be switched: ${e.message}",
            )
            ToolExecutionResult.success(r, json.encodeToString(CameraTorchResult.serializer(), r))
        }
    }

    private fun torchCameraId(manager: CameraManager, front: Boolean): String? {
        val ids = runCatching { manager.cameraIdList }.getOrNull().orEmpty()
        var anyFlash: String? = null
        for (id in ids) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            val isFront = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            if (isFront == front) return id
            if (anyFlash == null) anyFlash = id
        }
        return anyFlash
    }

    /**
     * Wait for the platform's own torch callback.
     *
     * The callback fires immediately on registration with the state in force, so a
     * matching value inside the timeout is real confirmation rather than a guess.
     * Anything else is reported as unconfirmed, because a tool that claims a torch is
     * on when the platform never said so leaves somebody in the dark reading a label.
     */
    private suspend fun awaitTorchState(manager: CameraManager, cameraId: String, want: Boolean): Boolean {
        val seen = CompletableDeferred<Boolean>()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId && !seen.isCompleted) seen.complete(enabled == want)
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id == cameraId && !seen.isCompleted) seen.complete(false)
            }
        }
        if (runCatching { manager.registerTorchCallback(callback, null) }.isFailure) return false
        return try {
            withTimeoutOrNull(1_000L) { seen.await() } == true
        } finally {
            runCatching { manager.unregisterTorchCallback(callback) }
        }
    }

    // ------------------------------------------------------------------
    // Audio route
    // ------------------------------------------------------------------

    private fun audioRoute(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.MODIFY_AUDIO_SETTINGS)?.let { return it }

        val manager = audioManager ?: run {
            val r = AudioRouteResult(
                success = false, requestedRoute = "",
                activeRoute = "none", confirmed = false,
                error = "This device has no AudioManager service, so there is no route to read or set.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(AudioRouteResult.serializer(), r))
        }

        val available = availableRouteNames(manager)
        val requested = (args["route"] as? String)?.lowercase()?.trim()

        if (requested.isNullOrBlank()) {
            val r = AudioRouteResult(
                success = true,
                requestedRoute = "",
                activeRoute = currentRouteName(manager),
                confirmed = true,
                availableRoutes = available,
                note = "Read only: no route was changed.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(AudioRouteResult.serializer(), r))
        }

        val accepted = when (requested) {
            "speaker" -> setSpeakerRoute(manager, true)
            "earpiece" -> setSpeakerRoute(manager, false)
            "bluetooth" -> setBluetoothRoute(manager)
            "headset" -> setWiredRoute(manager)
            else -> null
        }

        if (accepted == null) {
            val r = AudioRouteResult(
                success = false,
                requestedRoute = requested,
                activeRoute = currentRouteName(manager),
                confirmed = false,
                availableRoutes = available,
                error = "Route '$requested' is not available on this device right now. Use speaker, " +
                    "earpiece, bluetooth or headset, and check that the device is connected.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(AudioRouteResult.serializer(), r))
        }

        val after = currentRouteName(manager)
        val confirmed = after == requested
        val r = AudioRouteResult(
            success = confirmed,
            requestedRoute = requested,
            activeRoute = after,
            confirmed = confirmed,
            availableRoutes = available,
            note = if (confirmed) {
                "The route was read back as $after after the switch."
            } else {
                "The switch was accepted but the route still reads as $after. An unplugged headset, a " +
                    "Bluetooth device that is not connected, or a call in progress can hold the route."
            },
            error = if (confirmed) null else "The route did not change to $requested.",
        )
        return ToolExecutionResult.success(r, json.encodeToString(AudioRouteResult.serializer(), r))
    }

    private fun availableRouteNames(manager: AudioManager): List<String> {
        val out = mutableListOf<String>()
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty()
        for (device in devices) {
            val name = nameForDeviceType(device.type)
            if (name != "other") out.add(name)
        }
        return out.distinct().sorted()
    }

    private fun currentRouteName(manager: AudioManager): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { manager.communicationDevice }.getOrNull()
            if (device != null) return nameForDeviceType(device.type)
        }
        if (runCatching { manager.isBluetoothScoOn }.getOrDefault(false)) return "bluetooth"
        if (runCatching { manager.isSpeakerphoneOn }.getOrDefault(false)) return "speaker"
        // No communication device and no speakerphone flag means the platform is using
        // the earpiece on a phone, which is the unmarked default.
        return "earpiece"
    }

    private fun nameForDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headset"
        else -> "other"
    }

    /** Unit when the platform accepted the change; null when the device is not there. */
    private fun setSpeakerRoute(manager: AudioManager, speaker: Boolean): Unit? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = runCatching { manager.availableCommunicationDevices }.getOrNull().orEmpty()
                .firstOrNull { it.type == wanted } ?: return null
            return if (manager.setCommunicationDevice(device)) Unit else null
        }
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = speaker
        return Unit
    }

    private fun setBluetoothRoute(manager: AudioManager): Unit? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { manager.availableCommunicationDevices }.getOrNull().orEmpty()
                .firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: return null
            return if (manager.setCommunicationDevice(device)) Unit else null
        }
        @Suppress("DEPRECATION")
        manager.startBluetoothSco()
        @Suppress("DEPRECATION")
        manager.isBluetoothScoOn = true
        return Unit
    }

    private fun setWiredRoute(manager: AudioManager): Unit? {
        val device = runCatching { manager.availableCommunicationDevices }.getOrNull().orEmpty()
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return null
        return if (manager.setCommunicationDevice(device)) Unit else null
    }

    // ------------------------------------------------------------------
    // Continuous listening
    // ------------------------------------------------------------------

    private fun listenStart(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.RECORD_AUDIO)?.let { return it }

        PermissionGrants.guardHardware(
            present = PermissionGrants.hasMicrophone(context),
            label = "microphone",
            why = "GURU can listen and transcribe what it hears",
        )?.let { return it }

        val maxSeconds = ((args["maxSeconds"] as? Number)?.toInt() ?: 120).coerceIn(5, 300)
        val startError = ContinuousListen.start(context, maxSeconds)

        val r = if (startError == null) {
            ListenStartResult(
                success = true,
                listening = true,
                startedAtMillis = ContinuousListen.startedAtMillis,
                maxSeconds = maxSeconds,
                note = "Listening now, inside the GURU process. Call listenStop to end the session and " +
                    "transcribe it; the session ends early if the process is killed, because this is not " +
                    "a foreground service.",
            )
        } else {
            ListenStartResult(
                success = false,
                listening = ContinuousListen.isListening(),
                maxSeconds = maxSeconds,
                note = "The session did not start.",
                error = startError,
            )
        }
        return ToolExecutionResult.success(r, json.encodeToString(ListenStartResult.serializer(), r))
    }

    private suspend fun listenStop(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.RECORD_AUDIO)?.let { return it }

        if (!ContinuousListen.isListening() && ContinuousListen.startedAtMillis == 0L) {
            val r = ListenStopResult(
                success = false,
                note = "No listen session was running, so there was nothing to stop.",
                error = "There is no open listen session; call listenStart first.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(ListenStopResult.serializer(), r))
        }

        val stopped = ContinuousListen.stop()
        val pcm = stopped.pcm
        if (pcm == null) {
            val r = ListenStopResult(
                success = false,
                capturedSeconds = 0.0,
                audioFound = false,
                note = "The session is closed.",
                error = stopped.error,
            )
            return ToolExecutionResult.success(r, json.encodeToString(ListenStopResult.serializer(), r))
        }

        val capturedSeconds = pcm.length().toDouble() / (16_000.0 * 2.0)
        val wav = File(context.cacheDir, "listen_${System.currentTimeMillis()}.wav")
        val wrapError = ContinuousListen.toWav(pcm, wav)
        pcm.delete()
        if (wrapError != null) {
            wav.delete()
            val r = ListenStopResult(
                success = false,
                capturedSeconds = capturedSeconds,
                audioFound = capturedSeconds > 0.0,
                note = "The session is closed but its audio could not be prepared for transcription.",
                error = wrapError,
            )
            return ToolExecutionResult.success(r, json.encodeToString(ListenStopResult.serializer(), r))
        }

        val recognition = withContext(Dispatchers.IO) {
            SpeechRecognition.transcribeWav(context, wav, null)
        }
        wav.delete()

        val r = when (recognition) {
            is SpeechRecognition.Result.Ok -> ListenStopResult(
                success = true,
                transcript = recognition.text,
                confidence = recognition.confidence,
                capturedSeconds = capturedSeconds,
                audioFound = true,
                note = "Transcribed the whole session in one pass. A live transcript needs a streaming " +
                    "recogniser, which this build does not carry.",
            )
            is SpeechRecognition.Result.Empty -> ListenStopResult(
                success = true,
                transcript = "",
                capturedSeconds = capturedSeconds,
                audioFound = true,
                note = "Audio was captured but contains no recognisable speech.",
            )
            is SpeechRecognition.Result.Failed -> ListenStopResult(
                success = false,
                capturedSeconds = capturedSeconds,
                audioFound = true,
                note = "The session ran from ${stopped.startedAtMillis} and is now closed.",
                error = "Audio was captured but transcription failed: ${recognition.reason}",
            )
        }
        return ToolExecutionResult.success(r, json.encodeToString(ListenStopResult.serializer(), r))
    }
}

/**
 * The camera and audio domain's wiring.
 */
object CameraAudioDomainModule : DomainToolModule {
    override val id: String = "camera_audio"

    override val registrations: List<ToolSetRegistration> = listOf(CameraAudioDomainDefinitions)

    override fun bindings(): Module = module {
        factory { CameraAudioDomainExecutor(get()) }
    }
}
