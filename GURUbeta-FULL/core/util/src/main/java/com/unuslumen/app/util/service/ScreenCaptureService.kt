// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service required for screen capture on Android 14+.
 *
 * Android requires MediaProjection to be acquired from a foreground service
 * that is running with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. That much was
 * always right, and it is why this service exists.
 *
 * ============================================================================
 * THE DEFECT THIS FIXES (issue 28): the type is not legal on every path
 * ============================================================================
 *
 * `startForeground` with the mediaProjection type is only legal while the app
 * holds the `android:project_media` app-op. The system grants that app-op when
 * the human accepts the MediaProjection consent dialog, and revokes it when the
 * projection ends. It is not a manifest permission and the app cannot grant it
 * to itself.
 *
 * The service was calling `startForeground(mediaProjection)` unconditionally, on
 * all three paths, so two of them ran it while holding no app-op:
 *
 *   1. ACTION_STOP with no live projection      -> SecurityException
 *   2. invalid extras (no consent token)        -> SecurityException
 *   3. valid consent token                      -> legal
 *
 * The SecurityException was uncaught, so the process died rather than degrading.
 * The stack trace in the issue is the system rejecting the call, not a bug in
 * the app-op bookkeeping.
 *
 * Worse, this replaced a milder bug. The previous version skipped
 * `startForeground` on the invalid path, and the system answered
 * ForegroundServiceDidNotStartInTimeException. The "fix" moved the call to every
 * path without checking that the type was legal on every path, so an app kill
 * became a process kill.
 *
 * ============================================================================
 * THE RULE, which is what makes every path correct rather than lucky
 * ============================================================================
 *
 * `startForeground` must be called exactly once per `startForegroundService`,
 * because the system kills the app if the contract is unmet. And it must be
 * called with a type the app can legally use at that instant. Those two
 * constraints disagree on the paths with no consent, so there are now two
 * entry points:
 *
 *   [enterForegroundAsMediaProjection]  only where a live consent token exists.
 *   [enterForegroundAsSpecialUse]       everywhere else. specialUse is declared
 *                                       in the manifest, is not gated on a
 *                                       per-session app-op, and satisfies the
 *                                       contract for this app on every version.
 *
 * Both catch SecurityException and degrade to a clean stop rather than a crash,
 * because a service that cannot legally be foreground must still leave without
 * taking the user's app down with it.
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

        /**
         * Whether a live projection is being served.
         *
         * Set true only by the path that actually establishes a projection, never
         * by a foreground call that exists only to satisfy the start contract. A
         * flag that says "running" for a service that is about to stop is the same
         * class of lie as issue 44's success flag, so it is set where the fact is.
         */
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
        /*
         * STOP PATH.
         *
         * ACTION_STOP is delivered with startService (see
         * DeviceControlToolExecutor.invalidateProjection), so there is no
         * startForeground contract to satisfy here. specialUse is still used as a
         * safety net: if any future caller switches to startForegroundService, the
         * contract is met with a type that is legal without a consent token. The
         * mediaProjection type is the one thing that must not be used here, because
         * by definition the projection may already be gone.
         */
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ScreenCaptureService: stop requested (projection live=${mediaProjection != null})")
            enterForegroundAsSpecialUse("stop")
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

        /*
         * INVALID-EXTRAS PATH.
         *
         * This one IS reached from startForegroundService (ensureProjection and
         * ScreenSightController.tryStartService both use it), so startForeground
         * must run before stopSelf or the app is killed. There is no consent token,
         * so the mediaProjection type would throw; specialUse is the legal answer.
         */
        if (resultCode == RESULT_CODE_UNSET || data == null) {
            Log.e(
                TAG,
                "ScreenCaptureService: invalid result code or data (code=$resultCode, data=${data != null}) " +
                    "— no consent to establish a projection, stopping cleanly",
            )
            enterForegroundAsSpecialUse("invalid-extras")
            stopSelf()
            return START_NOT_STICKY
        }

        /*
         * HAPPY PATH.
         *
         * A consent token exists, so the system has already granted the
         * project_media app-op and the mediaProjection type is legal. Enter
         * foreground FIRST, because getMediaProjection requires the service to
         * already be a mediaProjection foreground service.
         */
        if (!enterForegroundAsMediaProjection()) {
            // The type was refused even with a token. That happens when the token
            // was consumed by an earlier projection, which is a clean "ask again",
            // not a crash. Satisfy the contract with specialUse so the process
            // survives, then leave.
            enterForegroundAsSpecialUse("projection-type-refused")
            mediaProjection = null
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            // SecurityException: consent token already consumed by a previous
            // projection. Clean exit, not a crash.
            Log.e(TAG, "ScreenCaptureService: getMediaProjection failed: ${e.message}")
            mediaProjection = null
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "ScreenCaptureService: getMediaProjection returned null — no projection to serve")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "ScreenCaptureService: MediaProjection stopped")
                mediaProjection = null
                isRunning = false
                stopSelf()
            }
        }, null)

        isRunning = true
        Log.d(TAG, "ScreenCaptureService: started with MediaProjection")
        return START_NOT_STICKY
    }

    /**
     * Enter the foreground with the mediaProjection type.
     *
     * Legal ONLY while a live consent token is held. Returns false when the system
     * refuses, so the caller can satisfy the contract with a type that is legal
     * and stop cleanly instead of dying.
     *
     * Below API 34 there is no type argument at all and no app-op check, so the
     * plain call always succeeds.
     */
    private fun enterForegroundAsMediaProjection(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "ScreenCaptureService: mediaProjection foreground type refused (${e.message}). " +
                    "This means no live project_media app-op, so no consent token is usable here.",
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "ScreenCaptureService: startForeground(mediaProjection) failed: ${e.message}")
            false
        }
    }

    /**
     * Enter the foreground with the specialUse type, which is always legal for
     * this app: the manifest declares it and FOREGROUND_SERVICE_SPECIAL_USE is
     * held. Used on every path with no live consent, purely to satisfy the
     * startForegroundService contract.
     *
     * A failure here is logged and swallowed rather than thrown. If even
     * specialUse is refused there is nothing legal left to try, and crashing the
     * process would turn a service-start problem into an app-kill, which is the
     * exact failure this change exists to remove.
     */
    private fun enterForegroundAsSpecialUse(reason: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "ScreenCaptureService: startForeground(specialUse) failed on the $reason path " +
                    "(${e.message}). Stopping without a foreground notification rather than crashing.",
            )
        }
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
