package com.unuslumen.app.data.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener service that gives Guru access to the device's notification stream.
 * Must be enabled by the user in Settings > Apps > guru > Notification Access.
 *
 * Once enabled, Guru can read incoming notifications, dismiss them, and react to them in real time.
 */
class GuruNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "guru"
        @Volatile
        var instance: GuruNotificationListener? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "GuruNotificationListener: service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "GuruNotificationListener: service destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "GuruNotificationListener: listener connected — Guru can now read notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "GuruNotificationListener: listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            Log.d(TAG, "GuruNotificationListener: notification posted from ${sbn.packageName}: ${sbn.notification.extras.getString(android.app.Notification.EXTRA_TITLE)}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            Log.d(TAG, "GuruNotificationListener: notification removed from ${sbn.packageName}")
        }
    }
}
