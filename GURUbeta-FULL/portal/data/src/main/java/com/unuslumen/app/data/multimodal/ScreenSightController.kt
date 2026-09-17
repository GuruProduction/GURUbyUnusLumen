package com.unuslumen.app.data.multimodal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.unuslumen.app.util.service.ScreenCaptureService

/**
 * Screen sight controller — the eyes for "Guru can always see the screen".
 *
 * Two capture paths, one switch (vision_enabled):
 *
 * 1. PRIMARY: persistent MediaProjection frame. The projection lives inside
 *    ScreenCaptureService (foreground service, Android 14+ compliant). Consent comes
 *    from MainActivity via DeviceControlToolExecutor statics. While the session is
 *    live we hold a VirtualDisplay + ImageReader permanently and can grab the latest
 *    frame at any instant with zero setup cost per grab. This is the path that makes
 *    live-style continuous sight possible later — the session just stays open.
 *
 * 2. FALLBACK: `screencap` via ShellExecutor (root > ADB > runtime). Used when the
 *    projection session is not live and cannot be started (e.g. consent token stale,
 *    app in background on Android 14+). Costs one shell exec per grab but has no
 *    consent requirements beyond root/ADB.
 *
 * When vision is OFF the controller does nothing — no captures, no session starts.
 * The persistent session is only (re)established while vision is on and consent
 * statics are live.
 */
class ScreenSightController(
    private val context: Context
) {

    companion object {
        private const val TAG = "guru"

        /** How long to wait for the projection session to come up on first grab. */
        private const val PROJECTION_WAIT_MS = 4000L
        private const val PROJECTION_WAIT_STEP_MS = 50L
    }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private var sessionWidth = 0
    private var sessionHeight = 0

    /** Whether the last grab came from the persistent session (true) or fallback (false). */
    @Volatile
    var lastSourceWasProjection: Boolean = false
        private set

    /**
     * Grab the current screen as a Bitmap. Returns null when sight cannot produce a
     * frame right now (vision infrastructure missing, consent dead and not re-grantable).
     * Never throws.
     */
    suspend fun grabScreen(): Bitmap? = try {
        // Primary: persistent projection session
        val fromProjection = grabFromProjection()
        if (fromProjection != null) {
            lastSourceWasProjection = true
            fromProjection
        } else {
            // Fallback: shell screencap (root > ADB > runtime)
            lastSourceWasProjection = false
            grabFromScreencap()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Sight: grab failed: ${e.message}")
        null
    }

    /**
     * Try to grab a frame from the persistent projection. Establishes the session if
     * the consent statics are live; returns null when they are not (caller should
     * re-run consent at some point — never from here, never a blocking dialog).
     */
    private suspend fun grabFromProjection(): Bitmap? {
        if (DeviceControlAccess.screenCaptureData == null) return null

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveProjectionAndroid14Plus() ?: return null
        } else {
            resolveProjectionLegacy() ?: return null
        }

        // Hold the display + reader open for the life of the session
        val metrics = currentMetrics()
        if (imageReader == null || sessionWidth != metrics.widthPixels || sessionHeight != metrics.heightPixels) {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = android.media.ImageReader.newInstance(
                metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2
            )
            virtualDisplay = projection.createVirtualDisplay(
                "GuruSight",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            sessionWidth = metrics.widthPixels
            sessionHeight = metrics.heightPixels
            // Virtual displays need a beat before the first frame renders
            kotlinx.coroutines.delay(120)
        }

        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels
            val bitmap = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / pixelStride,
                metrics.heightPixels, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
            if (cropped !== bitmap) bitmap.recycle()
            cropped
        } finally {
            image.close()
        }
    }

    /** Android 14+ projection resolution — session lives inside ScreenCaptureService. */
    private suspend fun resolveProjectionAndroid14Plus(): android.media.projection.MediaProjection? {
        var existing = ScreenCaptureService.mediaProjection
        if (existing != null) return existing
        val started = tryStartService()
        if (!started) return null
        // Bounded wait for the service to finish starting
        var waited = 0L
        while (ScreenCaptureService.mediaProjection == null && waited < PROJECTION_WAIT_MS) {
            kotlinx.coroutines.delay(PROJECTION_WAIT_STEP_MS)
            waited += PROJECTION_WAIT_STEP_MS
        }
        return ScreenCaptureService.mediaProjection
    }

    /** Legacy projection resolution — token held in DeviceControlToolExecutor companion. */
    private fun resolveProjectionLegacy(): android.media.projection.MediaProjection? {
        DeviceControlAccess.liveProjection?.let { return it }
        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(
                DeviceControlAccess.screenCaptureResultCode,
                DeviceControlAccess.screenCaptureData!!
            )
            DeviceControlAccess.liveProjection = projection
            projection
        } catch (e: Exception) {
            // Token consumed or invalid — needs fresh consent
            Log.w(TAG, "Sight: projection token invalid: ${e.message}")
            DeviceControlAccess.clearConsent()
            null
        }
    }

    /**
     * Start ScreenCaptureService with the stored consent. Catches the
     * ForegroundServiceStartNotAllowedException the platform throws when the app is in
     * the background — that's a clean "not now", not a crash.
     */
    private fun tryStartService(): Boolean {
        return try {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, DeviceControlAccess.screenCaptureResultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, DeviceControlAccess.screenCaptureData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sight: cannot start capture service from here: ${e.message}")
            false
        }
    }

    /** Fallback: shell screencap to a temp file, decode, clean up. */
    private suspend fun grabFromScreencap(): Bitmap? {
        val shell = com.unuslumen.app.util.shell.ShellExecutor(context)
        val path = "${context.cacheDir.absolutePath}/sight_${System.currentTimeMillis()}.png"
        val result = shell.execute("screencap -p '$path'")
        val file = java.io.File(path)
        if (!result.success || !file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
        file.delete()
        return bitmap
    }

    private fun currentMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        // DisplayMetrics constructed fresh has densityDpi = 0, and createVirtualDisplay
        // rejects a non-positive density ("Virtual display density must be positive").
        // Fill from resources (always valid) or estimate from the density scale.
        if (metrics.densityDpi <= 0) {
            val resMetrics = context.resources.displayMetrics
            metrics.densityDpi = if (resMetrics.densityDpi > 0) {
                resMetrics.densityDpi
            } else {
                (metrics.density * 160f).toInt().coerceAtLeast(120)
            }
            if (metrics.density <= 0f && resMetrics.density > 0f) metrics.density = resMetrics.density
        }
        return metrics
    }

    /** Release sight resources. Called when vision is switched off. */
    fun shutdown() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        sessionWidth = 0
        sessionHeight = 0
    }

    /**
     * Static bridge into DeviceControlToolExecutor's consent + projection state.
     * The consent flow (MainActivity broadcast → onActivityResult → statics) is
     * already built and works; sight reads from it rather than duplicating it.
     */
    internal object DeviceControlAccess {
        var screenCaptureResultCode: Int
            get() = com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureResultCode
            set(value) { com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureResultCode = value }
        var screenCaptureData: Intent?
            get() = com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureData
            set(value) { com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureData = value }

        /** Pre-14 path projection held by DeviceControlToolExecutor's companion. */
        var liveProjection: android.media.projection.MediaProjection?
            get() = com.unuslumen.app.data.tools.DeviceControlToolExecutor.getLiveProjection()
            set(value) { com.unuslumen.app.data.tools.DeviceControlToolExecutor.setLiveProjection(value) }

        fun clearConsent() {
            com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureData = null
            com.unuslumen.app.data.tools.DeviceControlToolExecutor.screenCaptureResultCode =
                ScreenCaptureService.RESULT_CODE_UNSET
        }
    }
}