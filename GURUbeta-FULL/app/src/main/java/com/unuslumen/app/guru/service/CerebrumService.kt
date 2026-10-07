// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.unuslumen.app.data.brain.cerebrum.CerebrumBoot
import com.unuslumen.app.util.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * CerebrumService — the persistent foreground service that hosts the brain
 * for the whole app lifetime (Phase F: foreground host).
 *
 * Android's foreground-service contract is that the platform REQUIRES the
 * persistent notification to be visible; users see "brain is active" exactly
 * like the heartbeat service precedent's "specialUse" pattern registered in
 * the app manifest. Start is STICKY; a swipe-away restart boots the same
 * encrypted vault path again (the vault auto-restores on re-boot; nothing is
 * lost on process teardown because the periodic save runs every 5 min plus
 * the final SIGTERM-path save JNI-side).
 */
class CerebrumService : Service() {

    companion object {
        private const val TAG = "guru_cerebrum"
        private const val CHANNEL_ID = "guru_cerebrum_channel"
        private const val NOTIFICATION_ID = 3011

        private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

        fun start(context: Context) {
            val intent = Intent(context, CerebrumService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CerebrumService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // The real brain boot lives in CerebrumBoot via the GuruApplication
        // wiring (Koin-driven, migration one-shot); the service exists to
        // keep the process resident exactly per Phase F's foreground contract.
        // Its re-fire path: swiping away, the START_STICKY flag restarts,
        // GuruApplication.onCreate runs again through the same encrypted
        // vault restore path. No new state to wire beyond host boot status!
        Log.d(TAG, "CerebrumService created (foreground), status=${CerebrumHostStatus()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // STICKY: the host survives swipe-away through this restart signal,
        // same shape as GuruCoreService.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        // Stop order: JNI stop does final-save; the zeroized pass drops.
        runCatching { CerebrumBoot.shutdown() }
            .onFailure { Log.e(TAG, "brain teardown on service destroy failed: ${it.message}") }
        serviceScope.cancel()
        Log.d(TAG, "CerebrumService destroyed (brain stopped + wiped RAM key holder)")
    }

    private fun CerebrumHostStatus(): String =
        runCatching { com.unuslumen.app.data.brain.cerebrum.CerebrumHost.statusJson() }.getOrElse { "{running:false}" }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // One-time heal: the pre-PhaseG channel was created at
            // IMPORTANCE_MIN; Android never lets app code raise a created
            // channel's importance, so a MIN-stamped device requires the
            // delete-then-recreate dance exactly once.
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null && existing.importance <= NotificationManager.IMPORTANCE_MIN) {
                nm.deleteNotificationChannel(CHANNEL_ID)
                Log.d(TAG, "old brain channel (MIN) deleted, recreating at default")
            }
            // Default importance: the persistent row stays in the shade per
            // the Phase F contract that "brain active" stays visible.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guru Brain",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "The encrypted memory brain for GURU"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Guru brain is active")
                .setContentText("Encrypted memory online")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Guru brain is active")
                .setContentText("Encrypted memory online")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}