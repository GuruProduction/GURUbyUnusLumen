// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import com.unuslumen.app.data.tools.notification.NotificationAccess
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The four shade tools: what is on screen right now.
 *
 * WHAT CHANGED AND WHY. Every one of these answered a listener that was not bound with
 * `ToolExecutionResult.success(...)`, so the model was told the call worked and handed an
 * empty list. That is the defect issue 337 names: a value a genuine result could also
 * produce is not a result, and "you have no notifications" is a claim this code had no
 * business making while it could see nothing. They now refuse through the same guard the
 * live tools use, and the refusal names the Settings screen.
 *
 * The second fault here was `appName`: it held the notification's TITLE rather than the
 * app's label, so a message from a friend reported the app as the friend's name.
 *
 * WHAT THIS SET IS FOR. It reads the shade, which is what the person can see and swipe
 * away. A notification that arrived and was dismissed is gone from here and still present
 * in `notificationStream`. Both questions are real and this set answers only the first.
 */
class NotificationToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** App labels already looked up, so a shade of twenty notifications costs one per app. */
    private val labelCache = mutableMapOf<String, String>()

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        NotificationToolDefinitions.LIST_NOTIFICATIONS -> getActiveNotifications(args)
        NotificationToolDefinitions.GET_NOTIFICATION -> getNotification(args)
        NotificationToolDefinitions.DISMISS_NOTIFICATION -> dismissNotification(args)
        NotificationToolDefinitions.DISMISS_ALL_NOTIFICATIONS -> dismissAll()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getActiveNotifications(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            NotificationAccess.guard(context)?.let { return@withContext it }

            val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 200)
            val service = GuruNotificationListener.instance
                ?: return@withContext notBound("listing the shade")

            val shade = service.shadeSnapshot()
            val notifs = shade.take(limit).map { it.toInfo() }

            val r = NotificationListResult(
                notifications = notifs,
                error = null,
                listenerRunning = true,
                listenerState = NotificationAccess.State.RUNNING.name,
                totalInShade = shade.size,
                truncated = shade.size > notifs.size,
            )
            ToolExecutionResult(
                rawJson = json.encodeToString(NotificationListResult.serializer(), r),
                resultData = r,
                resultObject = null,
                success = true,
                error = null,
            )
        }

    private suspend fun getNotification(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val packageName = args["packageName"] as? String
                ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
            val notificationId = (args["notificationId"] as? Number)?.toInt()
                ?: return@withContext ToolExecutionResult.error("Missing 'notificationId'")

            NotificationAccess.guard(context)?.let { return@withContext it }
            val service = GuruNotificationListener.instance
                ?: return@withContext notBound("reading one notification")

            val sbn = service.shadeSnapshot()
                .firstOrNull { it.packageName == packageName && it.id == notificationId }

            if (sbn == null) {
                val r = NotificationDetailResult(
                    notification = null,
                    error = "No notification from $packageName with id $notificationId is in the " +
                        "shade. It may have been dismissed, or it may have arrived and gone before " +
                        "this call, in which case notificationStream still has it.",
                    listenerRunning = true,
                    listenerState = NotificationAccess.State.RUNNING.name,
                )
                return@withContext ToolExecutionResult(
                    rawJson = json.encodeToString(NotificationDetailResult.serializer(), r),
                    resultData = r,
                    resultObject = null,
                    success = false,
                    error = r.error,
                )
            }

            val r = NotificationDetailResult(
                notification = sbn.toDetail(),
                error = null,
                listenerRunning = true,
                listenerState = NotificationAccess.State.RUNNING.name,
            )
            ToolExecutionResult(
                rawJson = json.encodeToString(NotificationDetailResult.serializer(), r),
                resultData = r,
                resultObject = null,
                success = true,
                error = null,
            )
        }

    private suspend fun dismissNotification(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val packageName = args["packageName"] as? String
                ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
            val notificationId = (args["notificationId"] as? Number)?.toInt()
                ?: return@withContext ToolExecutionResult.error("Missing 'notificationId'")

            NotificationAccess.guard(context)?.let { return@withContext it }
            val service = GuruNotificationListener.instance
                ?: return@withContext notBound("dismissing a notification")

            // The platform key is what dismiss takes. Building it from the package and the id
            // is what the old code did, and it is wrong for an app that posts several
            // notifications under one tag, so the live shade entry supplies its own key.
            val target = service.shadeSnapshot()
                .firstOrNull { it.packageName == packageName && it.id == notificationId }

            if (target == null) {
                val r = NotificationActionResult(
                    success = false,
                    error = "No notification from $packageName with id $notificationId is in the shade.",
                    listenerRunning = true,
                    listenerState = NotificationAccess.State.RUNNING.name,
                )
                return@withContext ToolExecutionResult(
                    rawJson = json.encodeToString(NotificationActionResult.serializer(), r),
                    resultData = r,
                    resultObject = null,
                    success = false,
                    error = r.error,
                )
            }

            service.dismissByKey(target.key)
            // Read the shade back: an ongoing or system notification refuses dismissal, and
            // reporting the call's return would claim a screen change that did not happen.
            val stillThere = service.shadeSnapshot().any { it.key == target.key }

            val r = NotificationActionResult(
                success = !stillThere,
                error = if (stillThere) {
                    "The shade still lists it, which is what Android does for an ongoing or system notification."
                } else null,
                listenerRunning = true,
                listenerState = NotificationAccess.State.RUNNING.name,
                dismissedCount = if (stillThere) 0 else 1,
            )
            ToolExecutionResult(
                rawJson = json.encodeToString(NotificationActionResult.serializer(), r),
                resultData = r,
                resultObject = null,
                success = r.success,
                error = r.error,
            )
        }

    private suspend fun dismissAll(): ToolExecutionResult = withContext(Dispatchers.IO) {
        NotificationAccess.guard(context)?.let { return@withContext it }
        val service = GuruNotificationListener.instance
            ?: return@withContext notBound("clearing the shade")

        val before = service.shadeSnapshot().count { it.isClearable }
        service.shadeSnapshot().filter { it.isClearable }.forEach { service.dismissByKey(it.key) }
        val remaining = service.shadeSnapshot().count { it.isClearable }

        val r = NotificationActionResult(
            success = remaining == 0,
            error = if (remaining > 0) "$remaining clearable notification(s) refused the dismiss." else null,
            listenerRunning = true,
            listenerState = NotificationAccess.State.RUNNING.name,
            dismissedCount = (before - remaining).coerceAtLeast(0),
        )
        ToolExecutionResult(
            rawJson = json.encodeToString(NotificationActionResult.serializer(), r),
            resultData = r,
            resultObject = null,
            success = r.success,
            error = r.error,
        )
    }

    /**
     * The refusal for a listener that passed the guard and then vanished.
     *
     * A guard passing means the state was RUNNING a moment ago, so this is the platform
     * unbinding between the check and the read. It is a failure rather than a success,
     * because nothing was read, and it says the fix is to ask again rather than to grant.
     */
    private fun notBound(what: String): ToolExecutionResult = ToolExecutionResult(
        rawJson = "",
        resultData = null,
        resultObject = null,
        success = false,
        error = "The notification listener stopped serving between the grant check and this read, " +
            "so $what did not happen. That clears by itself; ask again in a few seconds.",
    )

    private fun StatusBarNotification.toInfo(): NotificationInfo {
        val extras = notification?.extras
        return NotificationInfo(
            id = id,
            packageName = packageName,
            appName = labelFor(packageName),
            title = text(extras, Notification.EXTRA_TITLE),
            text = text(extras, Notification.EXTRA_TEXT),
            subText = text(extras, Notification.EXTRA_SUB_TEXT),
            postTime = postTime,
            isOngoing = isOngoing,
            isClearable = isClearable && !isGroupSummary(),
            category = notification?.category.orEmpty(),
            key = key ?: "$packageName:$id",
        )
    }

    private fun StatusBarNotification.toDetail(): NotificationDetail {
        val extras = notification?.extras
        val actions = notification?.actions
            ?.mapIndexedNotNull { index, action ->
                action ?: return@mapIndexedNotNull null
                NotificationAction(
                    title = action.title?.toString().orEmpty(),
                    actionIntent = action.actionIntent != null,
                    index = index,
                )
            }
            .orEmpty()

        return NotificationDetail(
            id = id,
            packageName = packageName,
            appName = labelFor(packageName),
            title = text(extras, Notification.EXTRA_TITLE),
            text = text(extras, Notification.EXTRA_TEXT),
            subText = text(extras, Notification.EXTRA_SUB_TEXT),
            bigText = text(extras, Notification.EXTRA_BIG_TEXT),
            summaryText = text(extras, Notification.EXTRA_SUMMARY_TEXT),
            postTime = postTime,
            isOngoing = isOngoing,
            isClearable = isClearable,
            category = notification?.category.orEmpty(),
            actions = actions,
            key = key ?: "$packageName:$id",
        )
    }

    /**
     * Whether this entry is the header the platform draws over a group.
     *
     * A summary cannot be dismissed on its own and disappears with its children, so
     * reporting it as clearable would tell a caller to remove something the platform
     * refuses to remove.
     */
    private fun StatusBarNotification.isGroupSummary(): Boolean =
        ((notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0

    /**
     * A notification extra as words.
     *
     * The values are `CharSequence` and in practice a `SpannableStringBuilder`, which is
     * where a cast to `String` throws. The type is checked rather than assumed because
     * some apps reuse an extra key for a different type entirely.
     */
    private fun text(extras: android.os.Bundle?, key: String): String =
        extras?.getCharSequence(key)?.toString()?.trim().orEmpty()

    /**
     * The app's label, which is what a person calls it.
     *
     * THE BUG THIS REPLACES. The field was filled with the notification's title, so a
     * WhatsApp message from a friend reported the app as the friend's name. Cached
     * because a shade of twenty notifications would otherwise cost twenty package lookups.
     */
    private fun labelFor(packageName: String): String {
        synchronized(labelCache) { labelCache[packageName]?.let { return it } }
        val label = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        synchronized(labelCache) { labelCache[packageName] = label }
        return label
    }
}
