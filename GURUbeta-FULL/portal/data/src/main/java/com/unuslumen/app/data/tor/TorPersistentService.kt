package com.unuslumen.app.data.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.torproject.jni.TorService

/**
 * Persistent foreground service that keeps Tor running indefinitely.
 *
 * Tor is infrastructure, not a feature. This service starts on first load
 * and runs until the app is uninstalled. It wraps the Guardian Project's
 * TorService in a foreground service so Android cannot kill Tor when the
 * app is backgrounded or memory is low.
 *
 * START_STICKY ensures Android restarts this service if it is killed
 * under extreme memory pressure. The BootBroadcastReceiver starts this
 * service on device reboot so Tor is available before the app is opened.
 *
 * The only scenario where Tor does not restart is a manual force-stop
 * from Settings. In that case, GuruApplication.onCreate() starts it again
 * when the user next opens the app.
 */
class TorPersistentService : Service() {

    companion object {
        private const val TAG = "TorPersistentService"
        private const val CHANNEL_ID = "tor_persistent_channel"
        private const val NOTIFICATION_ID = 3002

        fun start(context: Context) {
            val intent = Intent(context, TorPersistentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var torService: TorService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TorService.LocalBinder ?: return
            torService = binder.service
            bound = true
            Log.d(TAG, "TorService bound — SOCKS:${torService?.socksPort}, HTTP:${torService?.httpTunnelPort}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            bound = false
            Log.w(TAG, "TorService disconnected, attempting to rebind")
            bindTorService()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 14+ requires the service type in startForeground. We declare
        // specialUse: Tor is permanent infrastructure, and dataSync's runtime cap
        // (~6h on Android 15+) kills the whole app via
        // ForegroundServiceDidNotStopInTimeException when the service never stops.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        bindTorService()
        Log.d(TAG, "TorPersistentService created — Tor is now persistent (specialUse)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {}
            bound = false
        }
        torService = null
        Log.d(TAG, "TorPersistentService destroyed")
    }

    private fun bindTorService() {
        val intent = Intent(this, TorService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tor Persistent",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Tor running for secure network access"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
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
                .setContentTitle("Tor active")
                .setContentText("Secure network access running")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Tor active")
                .setContentText("Secure network access running")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}