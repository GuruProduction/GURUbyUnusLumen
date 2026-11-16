// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * The shade tools' payloads, each carrying the listener's state.
 *
 * WHY EVERY RESULT CARRIES THE STATE. A read from a disabled listener and a read from a
 * working listener that found nothing are different facts, and the old payloads could not
 * tell them apart: both were an empty list with a null error. The three extra fields are
 * what make the difference visible without the caller having to parse a sentence.
 *
 * `key` is the platform's own notification key. It is on every payload because dismiss
 * and action take it, and a caller that read a notification here should not have to
 * reconstruct an identifier from a package name and an id: that reconstruction is wrong
 * for any app posting several notifications under one tag.
 */
@Serializable
data class NotificationInfo(
    val id: Int,
    val packageName: String,
    /** The app's display label, not the notification's title. It was the title, which was a bug. */
    val appName: String,
    val title: String,
    val text: String,
    val subText: String,
    val postTime: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String,
    /** The platform key, which is what dismiss and action accept. */
    val key: String? = null,
) : ToolResultData

@Serializable
data class NotificationAction(
    val title: String,
    val actionIntent: Boolean,
    /** The position in the notification's action list, which is what notificationAction takes. */
    val index: Int = -1,
) : ToolResultData

@Serializable
data class NotificationDetail(
    val id: Int,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String,
    val bigText: String,
    val summaryText: String,
    val postTime: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String,
    val actions: List<NotificationAction>,
    val key: String? = null,
) : ToolResultData

@Serializable
data class NotificationListResult(
    val notifications: List<NotificationInfo>,
    val error: String?,
    /** True only when the service was bound, so an empty list is a real answer. */
    val listenerRunning: Boolean = false,
    val listenerState: String? = null,
    /** How many were in the shade before the limit was applied. */
    val totalInShade: Int = 0,
    /** True when the limit cut the list, so a count is not read as the whole shade. */
    val truncated: Boolean = false,
) : ToolResultData

@Serializable
data class NotificationDetailResult(
    val notification: NotificationDetail?,
    val error: String?,
    val listenerRunning: Boolean = false,
    val listenerState: String? = null,
) : ToolResultData

@Serializable
data class NotificationActionResult(
    val success: Boolean,
    val error: String?,
    val listenerRunning: Boolean = false,
    val listenerState: String? = null,
    /** How many the call actually removed, read back from the shade rather than assumed. */
    val dismissedCount: Int = 0,
) : ToolResultData
