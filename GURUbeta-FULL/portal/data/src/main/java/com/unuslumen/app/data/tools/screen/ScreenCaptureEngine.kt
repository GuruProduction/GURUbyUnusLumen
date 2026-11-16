// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.unuslumen.app.util.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureEngine — the one place that turns a MediaProjection into a picture or a video.
 *
 * WHY THIS EXISTS. There were four capture paths in the app and three of them were wrong.
 * `takeScreenshot` shelled out to `screencap`, which needs root or shell UID and therefore
 * fails for every ordinary install (issue 41). `recordScreen` shelled out to `screenrecord`,
 * with the same problem plus a zero-byte file reported as a success (issue 43). Only
 * `captureScreen` used MediaProjection, which is the mechanism the platform actually offers
 * to any app with consent, and it worked.
 *
 * So this file takes the path that worked, generalises it, and everything else calls it.
 * Four copies of a projection lifecycle is four places for the consent token to be consumed
 * twice, and a consumed token is the difference between a screenshot and a SecurityException.
 *
 * WHAT THE CONSENT FLOW IS, AND WHY THIS FILE DOES NOT REPLACE IT. The dialog needs an
 * Activity. MainActivity owns that: it shows the consent dialog and writes the result into
 * [consent]. Every capture here reads that, exactly as the working path already did. A tool
 * has no Activity, so a tool can never show the dialog itself, and pretending otherwise
 * would be the same lie as a permission refused in an empty list.
 *
 * RECORDING IS A REAL ENCODER, NOT A SHELL. A VirtualDisplay is given a MediaCodec surface
 * instead of an ImageReader, and a MediaMuxer writes the H.264 stream to an MP4. That is
 * what `screenrecord` does internally, and it is why the file plays: the muxer writes the
 * index when it stops, which a killed process never gets the chance to do.
 */
object ScreenCaptureEngine {

    private const val TAG = "guru"
    private const val SCREENSHOT_DIR = "screenshots"
    private const val RECORDING_DIR = "recordings"

    /** How long a first frame may take before the grab is reported as no frame. */
    private const val FRAME_WAIT_MS = 2_000L
    private const val FRAME_POLL_MS = 40L

    /** How long to wait for the capture service to publish its projection. */
    private const val SERVICE_WAIT_MS = 3_000L

    /** The encoder settings that make a recording playable on a phone. */
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL_SECONDS = 1
    private const val BIT_RATE = 8_000_000

    /**
     * The consent the person gave, as MainActivity recorded it.
     *
     * A token is single-use: it establishes exactly one MediaProjection, and any second
     * `getMediaProjection` with the same token throws SecurityException. That is why the
     * live projection is held here rather than recreated per call.
     */
    @Volatile
    var consentResultCode: Int = ScreenCaptureService.RESULT_CODE_UNSET
    @Volatile
    var consentData: Intent? = null

    /** The live projection, whichever route established it. */
    private var projection: MediaProjection? = null

    /** True when the projection was created by [ScreenCaptureService] and is not ours to stop. */
    private var projectionFromService = false

    /** The frame path's display and reader, held open so a second grab costs no setup. */
    private var grabDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var grabWidth = 0
    private var grabHeight = 0

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------

    /** Whether the person has granted screen capture in this app run. */
    fun hasConsent(): Boolean =
        consentData != null && consentResultCode != ScreenCaptureService.RESULT_CODE_UNSET

    /**
     * Record a consent the person just gave.
     *
     * Below Android 14 the projection is created here immediately, because the token is
     * single-use and holding it until the first capture would mean a later failure that
     * looks like a permission problem. On 14 and later the projection has to be created
     * inside the mediaProjection foreground service, so the service is started instead and
     * the capture path reads what it publishes.
     */
    fun acceptConsent(context: Context, resultCode: Int, data: Intent) {
        consentResultCode = resultCode
        consentData = data

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startCaptureService(context, resultCode, data)
        } else {
            projection = runCatching {
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                manager.getMediaProjection(resultCode, data)
            }.getOrNull()
            projectionFromService = false
        }
    }

    /** Forget a consent, so the next attempt asks for a fresh one rather than failing. */
    fun clearConsent(context: Context) {
        releaseGrabSession()
        if (!projectionFromService) {
            runCatching { projection?.stop() }
        }
        projection = null
        projectionFromService = false
        consentData = null
        consentResultCode = ScreenCaptureService.RESULT_CODE_UNSET

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ScreenCaptureService.isRunning
        ) {
            runCatching {
                context.startService(
                    Intent(context, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    },
                )
            }
        }
    }

    /** Why a capture cannot run right now, or null when it can. One sentence, actionable. */
    fun refusal(context: Context): String? {
        if (!hasConsent()) {
            return "Screen capture has not been allowed yet, so nothing was captured. Ask your " +
                "human to run requestScreenCapturePermission, tap Allow on the system dialog, " +
                "and then try again."
        }

        // The consent is stored but every route to a projection failed. Clearing it means
        // the next attempt asks for a fresh dialog rather than repeating this failure.
        val live = ensureProjection(context)
        if (live == null) {
            clearConsent(context)
            return "The screen capture session is no longer live, which happens when the phone " +
                "restarts a projection or the app is put in the background. Ask your human to run " +
                "requestScreenCapturePermission and allow the dialog again."
        }
        return null
    }

    // ------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------

    /**
     * Grab the current screen.
     *
     * Returns the file it wrote, or a failure that says which of the three things went wrong:
     * no consent, no projection, or no frame. Those need different answers and a null would
     * collapse them into one.
     */
    suspend fun screenshot(context: Context, filenamePrefix: String = "screenshot"): ScreenshotOutcome =
        withContext(Dispatchers.IO) {
            refusal(context)?.let { return@withContext ScreenshotOutcome.Refused(it) }

            val live = ensureProjection(context)
                ?: return@withContext ScreenshotOutcome.Refused(
                    "The screen capture session ended between the check and the grab. Ask again in a moment."
                )

            val frame = grabFrame(context, live)
                ?: return@withContext ScreenshotOutcome.NoFrame(
                    "The projection is live and no frame arrived within ${FRAME_WAIT_MS}ms. That " +
                        "happens when the screen is off or the display is between configurations; " +
                        "it is not a permission problem."
                )

            val dir = File(context.filesDir, SCREENSHOT_DIR).apply { mkdirs() }
            val file = File(dir, "${filenamePrefix}_${System.currentTimeMillis()}.png")
            val written = runCatching {
                java.io.FileOutputStream(file).use { out ->
                    frame.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                frame.recycle()
                file.length()
            }.getOrNull()

            if (written == null || written <= 0L) {
                runCatching { file.delete() }
                return@withContext ScreenshotOutcome.Failed(
                    "The frame was captured and could not be written to ${file.absolutePath}."
                )
            }

            ScreenshotOutcome.Captured(file.absolutePath, written)
        }

    /** One frame, or null when none arrives inside the wait. */
    private fun grabFrame(context: Context, live: MediaProjection): Bitmap? {
        val metrics = displayMetrics(context)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null

        if (imageReader == null || grabWidth != metrics.widthPixels || grabHeight != metrics.heightPixels) {
            releaseGrabSession()
            imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2,
            )
            grabDisplay = live.createVirtualDisplay(
                "GuruCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null,
            )
            grabWidth = metrics.widthPixels
            grabHeight = metrics.heightPixels
        }

        // Poll rather than sleep once and guess. A display created a moment ago needs a
        // beat to render its first frame, and a fixed sleep either wastes time or misses.
        val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
        var image: Image? = null
        while (System.currentTimeMillis() < deadline) {
            image = imageReader?.acquireLatestImage()
            if (image != null) break
            Thread.sleep(FRAME_POLL_MS)
        }

        val acquired = image ?: return null
        return try {
            acquired.toBitmap(grabWidth, grabHeight)
        } finally {
            acquired.close()
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /** The live recorder, or null when none is running. */
    private var recorder: Recorder? = null

    /**
     * Start recording. Returns the file the recording will be written to.
     *
     * A recording already running is refused rather than replaced, because replacing it
     * silently loses the earlier one and the person has no way to know.
     */
    suspend fun startRecording(
        context: Context,
        durationSeconds: Int,
    ): RecordingOutcome = withContext(Dispatchers.IO) {
        if (recorder != null) {
            return@withContext RecordingOutcome.AlreadyRecording(recorder!!.path)
        }

        refusal(context)?.let { return@withContext RecordingOutcome.Refused(it) }

        val live = ensureProjection(context)
            ?: return@withContext RecordingOutcome.Refused(
                "The screen capture session ended before recording could start."
            )

        val metrics = displayMetrics(context)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return@withContext RecordingOutcome.Failed("The display reported no usable size.")
        }

        val dir = File(context.filesDir, RECORDING_DIR).apply { mkdirs() }
        val path = File(dir, "recording_${System.currentTimeMillis()}.mp4").absolutePath

        val started = runCatching {
            Recorder.start(
                context = context,
                projection = live,
                path = path,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                durationSeconds = durationSeconds,
            )
        }

        started.exceptionOrNull()?.let { error ->
            Log.e(TAG, "startRecording failed: ${error.message}")
            return@withContext RecordingOutcome.Failed(
                "The video encoder refused to start: ${error.message}. This is a device or " +
                    "encoder limit rather than a permission problem.",
            )
        }

        val running = started.getOrNull()
            ?: return@withContext RecordingOutcome.Failed("The recorder produced no session.")

        recorder = running
        RecordingOutcome.Started(path, durationSeconds)
    }

    /**
     * Stop recording and report the file.
     *
     * This is where the old code lied. It reported success on a file that existed and was
     * zero bytes, because existence is not content. The rule here is bytes: a recording
     * with none is a failure that says so, and the muxer having stopped decides whether the
     * index was written and the file will actually play.
     */
    suspend fun stopRecording(): RecordingOutcome = withContext(Dispatchers.IO) {
        val running = recorder
            ?: return@withContext RecordingOutcome.NotRecording

        recorder = null

        val result = runCatching { running.stop() }
        result.exceptionOrNull()?.let { error ->
            Log.e(TAG, "stopRecording failed: ${error.message}")
            return@withContext RecordingOutcome.Failed(
                "The recorder failed while finishing the file: ${error.message}. The file at " +
                    "${running.path} is incomplete and will not play.",
            )
        }

        val bytes = File(running.path).let { if (it.isFile) it.length() else 0L }
        if (bytes <= 0L) {
            runCatching { File(running.path).delete() }
            return@withContext RecordingOutcome.Failed(
                "The recording wrote no frames in ${running.elapsedSeconds()}s, so there is " +
                    "nothing to play and the empty file has been removed. Recording the screen " +
                    "needs the display to be on and changing.",
            )
        }

        RecordingOutcome.Stopped(running.path, bytes)
    }

    /** Whether a recording is running, for a status report that must not guess. */
    fun isRecording(): Boolean = recorder != null

    /** The path a running recording is being written to, or null when none is running. */
    fun recordingPath(): String? = recorder?.path

    /**
     * The live projection for the pre-Android-14 sight path.
     *
     * The sight controller keeps its own bridge into the older API, and it must read the
     * same projection this engine created rather than making a second one. A second
     * `getMediaProjection` from one consent token throws, so returning this is what stops
     * the two paths from consuming each other's token.
     */
    fun liveProjectionForLegacy(): MediaProjection? = if (projectionFromService) null else projection

    // ------------------------------------------------------------------
    // The projection
    // ------------------------------------------------------------------

    /**
     * A live projection, creating one if the consent allows it.
     *
     * THREE ROUTES, and the differences matter. On Android 14 and later the projection must
     * be created by the mediaProjection foreground service, so the service is started and
     * waited for with a bound rather than a busy loop. Below 14 the projection is created
     * directly from the token. Either way a token establishes exactly one projection, which
     * is why the live one is cached and reused rather than recreated per call.
     */
    private fun ensureProjection(context: Context): MediaProjection? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ScreenCaptureService.mediaProjection?.let {
                projection = it
                projectionFromService = true
                return it
            }

            val data = consentData ?: return null
            startCaptureService(context, consentResultCode, data)

            val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                ScreenCaptureService.mediaProjection?.let {
                    projection = it
                    projectionFromService = true
                    return it
                }
                Thread.sleep(FRAME_POLL_MS)
            }
            return null
        }

        projection?.let { return it }
        val data = consentData ?: return null

        return runCatching {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            // The platform types this nullable, so it is checked rather than trusted: a null
            // here means the consent was refused, and treating it as present would throw on
            // the very next call instead of reporting the refusal.
            val created = manager.getMediaProjection(consentResultCode, data)
                ?: return@runCatching null

            projection = created
            projectionFromService = false
            created.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // The person or the system ended the projection. Dropping it here means
                    // the next capture asks for a fresh consent instead of throwing on a
                    // dead object.
                    if (projection === created) projection = null
                }
            }, null)
            created
        }.getOrNull()
    }

    private fun startCaptureService(context: Context, resultCode: Int, data: Intent) {
        runCatching {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            // ForegroundServiceStartNotAllowedException when the app is in the background on
            // Android 14+. A clean "not now", not a crash.
            Log.e(TAG, "startCaptureService refused: ${it.message}")
        }
    }

    private fun releaseGrabSession() {
        runCatching { grabDisplay?.release() }
        grabDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        grabWidth = 0
        grabHeight = 0
    }

    /** Release everything. Called when the app is shutting the projection down. */
    fun shutdown() {
        releaseGrabSession()
        runCatching { recorder?.stop() }
        recorder = null
        projection = null
        projectionFromService = false
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The real display size.
     *
     * `currentWindowMetrics` gives the right answer on 11 and later, but it leaves density
     * at zero on a freshly built DisplayMetrics, and `createVirtualDisplay` rejects a
     * non-positive density. The resources fallback is not a nicety: without it the display
     * creation throws on every modern phone.
     */
    private fun displayMetrics(context: Context): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val resources = context.resources.displayMetrics
        if (metrics.densityDpi <= 0) {
            metrics.densityDpi = when {
                resources.densityDpi > 0 -> resources.densityDpi
                metrics.density > 0f -> (metrics.density * 160f).toInt()
                else -> DisplayMetrics.DENSITY_DEFAULT
            }
        }
        if (metrics.density <= 0f && resources.density > 0f) metrics.density = resources.density
        return metrics
    }

    /**
     * An Image as a Bitmap, cropped to the display.
     *
     * A framebuffer row is padded to an alignment, so the buffer is wider than the screen
     * by `rowPadding / pixelStride`. Copying it without cropping yields an image with a
     * strip of garbage down the right side, which is the classic screenshot bug.
     */
    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(buffer)

        if (padded.width == width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    sealed interface ScreenshotOutcome {
        data class Captured(val path: String, val sizeBytes: Long) : ScreenshotOutcome
        data class Refused(val reason: String) : ScreenshotOutcome
        data class NoFrame(val reason: String) : ScreenshotOutcome
        data class Failed(val reason: String) : ScreenshotOutcome
    }

    sealed interface RecordingOutcome {
        data class Started(val path: String, val durationSeconds: Int) : RecordingOutcome
        data class AlreadyRecording(val path: String) : RecordingOutcome
        data class Stopped(val path: String, val sizeBytes: Long) : RecordingOutcome
        data object NotRecording : RecordingOutcome
        data class Refused(val reason: String) : RecordingOutcome
        data class Failed(val reason: String) : RecordingOutcome
    }

    // ------------------------------------------------------------------
    // The recorder
    // ------------------------------------------------------------------

    /**
     * One recording session: a VirtualDisplay feeding a MediaCodec, muxed to an MP4.
     *
     * WHY A CODEC SURFACE RATHER THAN AN IMAGE READER. A reader gives bitmaps, which would
     * have to be re-encoded frame by frame by hand. Handing the display the codec's own
     * input surface means the platform composites straight into the encoder, which is how
     * `screenrecord` works and why it costs no per-frame CPU in this app.
     *
     * THE MUXER IS WHY THE FILE PLAYS. It writes the MP4 index when `stop` is called. A
     * process killed instead leaves a file with bytes and no index, which no player opens,
     * and that is the failure the old shell-based recorder could not tell apart from a
     * good recording.
     */
    private class Recorder private constructor(
        val path: String,
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val display: VirtualDisplay,
        private val startedAt: Long,
    ) {
        /** Set once stop has been asked for, so nothing drains after the muxer is stopped. */
        private val stopping = AtomicBoolean(false)

        /** Set by the drain thread when it saw the encoder's end-of-stream flag. */
        private val drained = AtomicBoolean(false)

        private var trackIndex = -1
        private var muxerStarted = false
        private var drainThread: Thread? = null

        fun elapsedSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000L

        /**
         * Stop the display and the muxer, in the order that makes the file valid.
         *
         * WHY ONLY ONE THREAD DRAINS. An encoder is not safe to dequeue from two threads at
         * once, and the obvious shape here - a background drain plus an inline flush in stop
         * - is exactly that race. So the background thread owns every dequeue for the whole
         * life of the session, stop asks the encoder for end-of-stream, and stop waits for
         * the drain thread to see it. The thread then stops the codec and the muxer itself,
         * because it is the last one touching them.
         *
         * ORDER MATTERS INSIDE THAT. The display goes first so no more frames arrive, then
         * end-of-stream flushes what the encoder holds, and the muxer is stopped only after
         * the final buffer has been written. Stopping the muxer earlier drops the last
         * frames and writes an index that no longer matches the data.
         */
        fun stop() {
            if (!stopping.compareAndSet(false, true)) return

            runCatching { display.release() }
            runCatching { codec.signalEndOfInputStream() }

            // Bounded: a wedged encoder must not hang the tool that asked to stop. If the
            // drain did not reach end-of-stream in time, the teardown below still runs and
            // the byte count decides whether the file is worth keeping.
            runCatching { drainThread?.join(4_000L) }

            if (!drained.get()) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }

        private fun startMuxer(format: MediaFormat) {
            if (muxerStarted) return
            trackIndex = runCatching { muxer.addTrack(format) }.getOrDefault(-1)
            if (trackIndex < 0) return
            runCatching { muxer.start() }.onSuccess { muxerStarted = true }
        }

        companion object {
            /**
             * Build and start a session, or throw with the encoder's own reason.
             *
             * A device without an H.264 encoder throws from `createEncoderByType`, and that
             * is worth surfacing verbatim: it is a device limit, not a permission.
             */
            fun start(
                context: Context,
                projection: MediaProjection,
                path: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                durationSeconds: Int,
            ): Recorder {
                val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }

                val codec = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                val inputSurface = codec.createInputSurface()
                codec.start()

                val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // The platform types createVirtualDisplay as nullable. A null here is a
                // real refusal by the system, so it is thrown with a sentence rather than
                // passed into the Recorder as a null display that would fail later with a
                // less useful message.
                val display = projection.createVirtualDisplay(
                    "GuruRecord",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null,
                ) ?: throw IllegalStateException(
                    "the platform refused to create the recording display, which usually means " +
                        "another projection owns the display",
                )

                val recorder = Recorder(
                    path = path,
                    codec = codec,
                    muxer = muxer,
                    display = display,
                    startedAt = System.currentTimeMillis(),
                )
                recorder.drainInBackground()

                // A duration is a ceiling, not a promise: the platform's own recorder
                // behaves the same way, and a person who asked for 30 seconds should not
                // have to remember to stop it.
                //
                // A plain background thread rather than a Handler on the main looper,
                // because the main looper is where the UI lives and a stop must never
                // run on it. It goes through stopRecording so the engine's own record of
                // "a recording is running" is cleared, otherwise the next start would be
                // refused by a session that had already finished.
                if (durationSeconds > 0) {
                    Thread {
                        runCatching { Thread.sleep(durationSeconds * 1000L) }
                        // Only stop if this recorder is still the live one, and compare by
                        // path rather than by identity: a person who stopped early and
                        // started another must not have the new one killed by this timer
                        // firing late.
                        stopIfStillRecording(recorder.path)
                    }.apply {
                        name = "guru-record-ceiling"
                        isDaemon = true
                        start()
                    }
                }

                return recorder
            }
        }

        /**
         * Drain the encoder continuously, and be the only thread that ever dequeues.
         *
         * The encoder fills a fixed number of output buffers and then blocks, so a recorder
         * that drained only on stop would stall after the first second and produce a file
         * with a handful of frames. This thread is what makes the file continuous.
         *
         * IT ALSO OWNS THE TEARDOWN. When end-of-stream arrives it stops the codec and the
         * muxer itself and marks the session drained, because it is the last thread touching
         * either. [stop] waits for that flag instead of racing this thread for buffers.
         */
        private fun drainInBackground() {
            drainThread = Thread {
                val info = MediaCodec.BufferInfo()
                var sawEndOfStream = false

                while (!sawEndOfStream) {
                    val index = runCatching {
                        codec.dequeueOutputBuffer(info, 10_000L)
                    }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)

                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Nothing yet. If stop has been asked for the encoder is
                            // flushing, so keep polling rather than spinning: the next
                            // dequeue is what collects the tail.
                            continue
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            startMuxer(codec.outputFormat)
                        }
                        index >= 0 -> {
                            val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                            if (buffer != null && muxerStarted && info.size > 0) {
                                runCatching { muxer.writeSampleData(trackIndex, buffer, info) }
                            }
                            runCatching { codec.releaseOutputBuffer(index, false) }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawEndOfStream = true
                            }
                        }
                    }
                }

                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
                drained.set(true)
            }.apply {
                name = "guru-screen-record"
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Stop a recording only if it is still the one at [path].
     *
     * The duration ceiling runs on its own thread and fires long after it was scheduled, so
     * by the time it does the person may have stopped that recording and started another.
     * Comparing paths is what keeps the old timer from killing the new recording.
     */
    private fun stopIfStillRecording(path: String) {
        if (recorder?.path != path) return
        kotlinx.coroutines.runBlocking { stopRecording() }
    }
}
