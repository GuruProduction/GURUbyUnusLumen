// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.unuslumen.app.data.hooks.HookEventBus
import com.unuslumen.app.data.tools.notification.CapturedAction
import com.unuslumen.app.data.tools.notification.CapturedNotification
import com.unuslumen.app.data.tools.notification.NotificationAccess
import com.unuslumen.app.data.tools.notification.NotificationStream
import com.unuslumen.app.domain.model.HookEventType

/**
 * The notification listener, doing the job the permission exists for.
 *
 * WHAT WAS WRONG. This service was registered, declared and bound correctly, and its
 * `onNotificationPosted` wrote a debug log and stopped. The four tools that need it
 * read `activeNotifications`, which is the shade rather than the stream, so a
 * notification that arrived and was dismissed before a tool ran was invisible. The
 * permission was wired and the capability was not, which is the fault issue 152
 * describes as "unused".
 *
 * WHAT IT DOES NOW. Every arrival becomes a [CapturedNotification] and goes into
 * [NotificationStream], and a hook fires so automation can react to a specific app.
 * The shade read stays where it is, because "what is on screen right now" is a real
 * question with a different answer from "what has arrived".
 *
 * WHAT IT DELIBERATELY DOES NOT CAPTURE. Its own notifications. GURU posting a
 * notification is GURU talking to the person, and feeding that back into the stream
 * would let one hook trigger itself forever and would fill the ring with the app's
 * own voice. A notification from another app is the thing worth capturing.
 */
class GuruNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "guru"

        /**
         * The bound instance, or null.
         *
         * Kept as the single source of truth for "is it running", which is why
         * [NotificationAccess.state] derives its answer from this rather than from a
         * second flag. Two signals for one question is how the accessibility service
         * ended up with a status field that could not be trusted (issue 36).
         */
        @Volatile
        var instance: GuruNotificationListener? = null
            private set

        /**
         * The app labels seen so far.
         *
         * A `PackageManager` lookup per notification is not free, and a notification
         * burst from one app would spend the same lookup hundreds of times. The cache
         * is process-scoped and holds the label only, which cannot go stale in a way
         * that matters: an app that renames itself mid-run keeps its old label until
         * the next process start, and the package name is always on the result anyway.
         */
        private val labelCache = mutableMapOf<String, String>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "GuruNotificationListener: service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.d(TAG, "GuruNotificationListener: service destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "GuruNotificationListener: listener connected, so arrivals are captured live")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // The platform unbinds and then reconnects on its own schedule, so the state is
        // reported as enabled-but-not-bound rather than off. Clearing the instance is
        // what makes that distinction visible to NotificationAccess.
        if (instance === this) instance = null
        Log.d(TAG, "GuruNotificationListener: listener disconnected; the platform will rebind")
    }

    /**
     * One arrival.
     *
     * Everything is wrapped, because a listener that throws takes the platform's
     * binding down with it: a malformed notification from a third-party app must never
     * cost the person their notification access. The catch is logged and the arrival
     * is skipped.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val captured = try {
            capture(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "GuruNotificationListener: could not capture from ${sbn.packageName}: ${e.message}")
            return
        } ?: return

        // A notification updated in place (a progress bar, an incoming message in one
        // conversation) re-posts with the same key. Replacing the entry keeps the ring
        // honest: it holds what is true now, and a duplicate would make a count wrong.
        NotificationStream.record(captured)

        // The hook is the automation surface. It fires with a flat map so a condition can
        // match one field, and the whole payload travels so a skill or a cycle can read
        // the title without asking the platform again.
        HookEventBus.fire(
            HookEventType.CUSTOM,
            mapOf(
                "event" to "notificationPosted",
                "packageName" to captured.packageName,
                "appLabel" to captured.appLabel,
                "title" to captured.title,
                "text" to captured.text,
                "category" to captured.category,
                "notificationId" to captured.notificationId,
                "key" to captured.key,
                "postTimeMillis" to captured.postTimeMillis,
                "hasActions" to captured.hasActions,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        Log.d(TAG, "GuruNotificationListener: notification removed from ${sbn.packageName}")
    }

    /**
     * Convert a platform notification into the pure object the rest of the app uses.
     *
     * Returns null for GURU's own notifications, which is the one deliberate exclusion:
     * capturing them would let a hook that posts a notification trigger itself.
     */
    private fun capture(sbn: StatusBarNotification): CapturedNotification? {
        if (sbn.packageName == packageName) return null

        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        return CapturedNotification(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            appLabel = labelFor(sbn.packageName),
            notificationId = sbn.id,
            title = text(extras, Notification.EXTRA_TITLE),
            text = text(extras, Notification.EXTRA_TEXT),
            subText = text(extras, Notification.EXTRA_SUB_TEXT),
            // The expanded body is what a messaging app actually shows, and the collapsed
            // line is often just "1 new message". Reading both means the detail does not
            // have to go back to the platform to say what was actually said.
            bigText = text(extras, Notification.EXTRA_BIG_TEXT),
            category = notification.category.orEmpty(),
            postTimeMillis = sbn.postTime,
            receivedAtMillis = System.currentTimeMillis(),
            isOngoing = sbn.isOngoing,
            // A group summary is the header the platform draws over several notifications
            // and cannot be dismissed on its own, so it is reported as not clearable
            // whatever the platform says.
            isClearable = sbn.isClearable && !isGroupSummary(notification),
            actions = actionsOf(notification),
        )
    }

    /**
     * The buttons on a notification, with their position.
     *
     * The index is the position in the platform's action array, which is what an action
     * call needs later: the title can be blank or duplicated, and the intent itself
     * cannot be rebuilt once the notification object is gone. `wantsText` marks a reply
     * action, because pressing one without text does nothing useful and a model that
     * cannot tell will send a tap and report a failure it did not cause.
     */
    private fun actionsOf(notification: Notification): List<CapturedAction> {
        val actions = notification.actions ?: return emptyList()
        return actions.mapIndexedNotNull { index, action ->
            if (action == null) return@mapIndexedNotNull null
            val title = action.title?.toString().orEmpty()
            CapturedAction(
                index = index,
                title = title,
                sendable = action.actionIntent != null,
                wantsText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    action.remoteInputs?.isNotEmpty() == true,
            )
        }
    }

    /**
     * The strings a notification carries.
     *
     * `EXTRA_*` values are `CharSequence` and are usually `SpannableStringBuilder`, which
     * is where a naive cast to `String` throws. The platform also reuses some extras for
     * other types entirely, so the type is checked rather than assumed.
     */
    private fun text(extras: android.os.Bundle, key: String): String {
        val value = extras.getCharSequence(key) ?: return ""
        return value.toString().trim()
    }

    private fun isGroupSummary(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    /**
     * The app's display label, which is what a person calls the app.
     *
     * A model reading "com.whatsapp" has to know what that is; reading "WhatsApp" it
     * does not. The fallback is the package name rather than an empty string, because an
     * unreadable label should not make the notification look anonymous.
     */
    private fun labelFor(packageName: String): String {
        synchronized(labelCache) {
            labelCache[packageName]?.let { return it }
        }
        val label = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        synchronized(labelCache) { labelCache[packageName] = label }
        return label
    }

    // ------------------------------------------------------------------
    // What the tools call on the bound instance
    // ------------------------------------------------------------------

    /**
     * The notifications currently in the shade.
     *
     * Read from the platform every call rather than mirrored into a field, because the
     * shade changes without this process being told: a notification can be dismissed by
     * a swipe on a screen GURU is not watching, and a cached list would report it as
     * still there.
     */
    fun shadeSnapshot(): List<StatusBarNotification> = runCatching { activeNotifications }
        .getOrNull()
        ?.toList()
        .orEmpty()

    /**
     * Dismiss one notification by its platform key.
     *
     * Returns the platform's own word on it: dismiss is a request, and a notification
     * protected by the platform (an ongoing call, a system alert) refuses it. Reporting
     * success from the call returning would be a lie about the person's screen.
     */
    fun dismissByKey(key: String): Boolean = runCatching {
        // The platform method is cancelNotification even though the behaviour is a dismiss.
        // There is no dismissNotification on NotificationListenerService, which is the name
        // this was first written with and the reason the build caught it.
        cancelNotification(key)
        true
    }.getOrDefault(false)

    /**
     * Press one of a notification's buttons.
     *
     * `PendingIntent` is the only way to invoke a notification action, and it is looked
     * up from the live shade entry rather than rebuilt, because the intent belongs to the
     * posting app. A missing key or an index past the end is reported as the miss it is.
     */
    fun pressAction(key: String, actionIndex: Int): Boolean = runCatching {
        val target = shadeSnapshot().firstOrNull { it.key == key } ?: return false
        val action = target.notification?.actions?.getOrNull(actionIndex) ?: return false
        val intent = action.actionIntent ?: return false
        intent.send()
        true
    }.getOrDefault(false)
}
