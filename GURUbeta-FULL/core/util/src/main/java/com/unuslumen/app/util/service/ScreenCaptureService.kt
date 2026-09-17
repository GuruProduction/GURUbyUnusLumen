package com.unuslumen.app.util.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service required for screen capture on Android 14+.
 * Android requires MediaProjection to be acquired from a foreground service
 * with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "guru"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.unuslumen.app.guru.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.unuslumen.app.guru.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /**
         * Sentinel for "no consent was stored". Must NOT be RESULT_OK (-1): Android's
         * RESULT_OK IS -1, so the old -1 default made every successful consent look
         * unset, the service bailed without startForeground, and the system threw
         * ForegroundServiceDidNotStartInTimeException (app-killing crash).
         */
        const val RESULT_CODE_UNSET = Int.MIN_VALUE

        var isRunning = false
            private set

        /** The MediaProjection created by this service. Other code reads this. */
        var mediaProjection: MediaProjection? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_STOP arrives via startService (not startForegroundService), so
        // startForeground is not mandatory here — but calling it is always legal and
        // guarantees the contract is satisfied on every path.
        if (intent?.action == ACTION_STOP) {
            enterForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_CODE_UNSET) ?: RESULT_CODE_UNSET
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == RESULT_CODE_UNSET || data == null) {
            Log.e(TAG, "ScreenCaptureService: invalid result code or data — cannot establish projection")
            // Started via startForegroundService → the system requires startForeground
            // before we may stop, on EVERY exit path, or the app is killed.
            enterForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        // Enter foreground FIRST, then acquire the projection
        enterForeground()

        // Create MediaProjection from within the foreground service
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            // SecurityException: consent token already consumed by a previous
            // projection. Clean exit, not a crash.
            Log.e(TAG, "ScreenCaptureService: getMediaProjection failed: ${e.message}")
            mediaProjection = null
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "ScreenCaptureService: MediaProjection stopped")
                mediaProjection = null
                stopSelf()
            }
        }, null)

        Log.d(TAG, "ScreenCaptureService: started with MediaProjection")
        return START_NOT_STICKY
    }

    /** Always-legal startForeground with the right type for the API level. */
    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
    }

    override fun onDestroy() {
        mediaProjection?.stop()
        mediaProjection = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Guru Screen Capture")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Used for screen capture by Guru"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}