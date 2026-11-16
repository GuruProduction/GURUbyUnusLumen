// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The shade tool set: what is visible in the notification panel right now.
 *
 * WHY THESE DESCRIPTIONS GREW. They were one line each, and a one-line description is
 * what let the old code report a dead listener as a success: nothing told the model that
 * an empty list could mean the app cannot see the shade at all. Every description now
 * states what the tool needs, what an empty result means, and which neighbouring tool
 * answers the question this one cannot.
 *
 * THE TWO SETS, AND WHICH TO USE. These read the shade: the notifications the person can
 * see and swipe away. `notificationStream` reads the arrivals: everything that came
 * through during this app run, including what was already dismissed. "What is on my
 * screen" is this set. "What did I miss" is the other.
 */
object NotificationToolDefinitions : ToolSetRegistration {
    const val LIST_NOTIFICATIONS = "getActiveNotifications"
    const val GET_NOTIFICATION = "getNotification"
    const val DISMISS_NOTIFICATION = "dismissNotification"
    const val DISMISS_ALL_NOTIFICATIONS = "dismissAllNotifications"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_NOTIFICATIONS,
            description = "List the notifications currently visible in the notification shade, " +
                "with the app, title, text and the key needed to dismiss or act on each one. " +
                "This is what is on screen now, not what has arrived: a notification the person " +
                "already swiped away is not here, and notificationStream is the tool that keeps it. " +
                "It needs Notification access, which is a Settings toggle rather than a dialog, and " +
                "a refusal names that screen. An empty list means the shade is genuinely empty, " +
                "because a listener that cannot see the shade refuses instead of answering nothing.",
            category = "notification",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "Maximum notifications to return, 1 to 200. Default 50.",
                ),
            ),
            // NOT declared. BIND_NOTIFICATION_LISTENER_SERVICE is a signature permission that
            // reads as denied on every phone, so the dispatcher's pre-flight would refuse this
            // tool everywhere, including on a phone where the listener is enabled and serving.
            // The real grant is read from Settings.Secure by NotificationAccess.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = GET_NOTIFICATION,
            description = "Read one notification in full by package name and id, including the " +
                "expanded body and every button it carries with that button's index. Use this " +
                "after getActiveNotifications narrowed the field, because the list gives a " +
                "truncated line and this gives what the notification actually says. Needs " +
                "Notification access, and a notification that is no longer in the shade is " +
                "reported as gone rather than as never having existed.",
            category = "notification",
            parameters = listOf(
                ToolParameter(
                    "packageName", ToolParameterType.String, true,
                    "Package name of the app that posted it, such as com.whatsapp.",
                ),
                ToolParameter(
                    "notificationId", ToolParameterType.Integer, true,
                    "The id getActiveNotifications reported for it.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = DISMISS_NOTIFICATION,
            description = "Dismiss one notification from the shade by package name and id. The " +
                "result reads the shade back, so a notification Android refuses to remove, such " +
                "as an ongoing call or a system alert, is reported as still there rather than as " +
                "dismissed. Use dismissAllNotifications to clear the panel, or " +
                "notificationDismiss to remove a notification by the key the stream reported.",
            category = "notification",
            parameters = listOf(
                ToolParameter(
                    "packageName", ToolParameterType.String, true,
                    "Package name of the app that posted it.",
                ),
                ToolParameter(
                    "notificationId", ToolParameterType.Integer, true,
                    "The id getActiveNotifications reported for it.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = DISMISS_ALL_NOTIFICATIONS,
            description = "Dismiss everything clearable from the notification shade. Ongoing and " +
                "system notifications refuse dismissal and are counted out rather than claimed, " +
                "and the result reports how many were actually removed by reading the shade " +
                "afterwards. This clears the panel; it does not touch the arrival history " +
                "notificationStream keeps.",
            category = "notification",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NotificationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
